package me.snowmii.dlss.mrt

import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.render.DlssCameraMotion
import me.snowmii.dlss.render.DlssCameraSample
import me.snowmii.dlss.render.DlssFrameMotion
import me.snowmii.dlss.render.DlssJitterOffset
import me.snowmii.dlss.render.DlssProjectionJitter
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * M-6's first capability: the previous-transform double buffer every dynamic world-pass velocity
 * writer consumes, and the object reprojection that extends the camera's with the object's own
 * motion.
 *
 * Terrain velocity (M-5) is a pure function of two frames' cameras, so it collapses into the one
 * reprojection matrix [DlssCameraMotion] publishes. An entity is not: its pixels sit where the
 * entity's *current* pose put them, and last frame they sat somewhere the camera reprojection
 * alone does not know. DLSS still needs, per pixel, where that surface point was in the previous
 * frame, so the velocity writer needs the entity's previous position - captured before the world
 * phase opens, keyed by the entity's stable integer id - and a reprojection composed from the
 * camera's plus the entity's own displacement.
 *
 * The buffer half of the invariant is pure lifecycle: captures must not become predecessors until
 * the frame boundary publishes them, a first observation has no predecessor, a reset forgets
 * everything, and an id absent from a frame is evicted so a reused id cannot inherit a dead
 * object's position. The math half wraps the object's displacement around the camera's published
 * reprojection, conjugated by the current jittered view-projection `Q = T(jitter) *
 * currentViewProjection`:
 *
 * ```
 * objectReprojection = camera.reprojection * Q * T(-objectDelta) * inverse(Q)
 * ```
 *
 * - mathematically the `DlssCameraMotion` composition with the object's camera-relative
 *   displacement folded into the camera's. A world-still object therefore reduces to the camera
 *   reprojection exactly, and a translating object reads its own NDC motion on top of the
 *   camera's. A reset frame composes nothing: the identity, exactly like the camera's own reset
 *   reprojection, so the velocity writer's reset flag keeps meaning what it meant.
 */
class MotionVectorObjectStateTest {
	private val RENDER_DIMENSIONS = DlssDimensions(1280, 720)
	private val tolerance = 1e-4f

	private val projection: Matrix4f = Matrix4f().setPerspective(
		Math.toRadians(70.0).toFloat(),
		RENDER_DIMENSIONS.width.toFloat() / RENDER_DIMENSIONS.height,
		1000f,
		0.05f,
		true,
	)

	/** The two frames jitter differently, so neither offset can quietly cancel the other. */
	private val previousOffset = DlssJitterOffset(0, pixelX = -0.44f, pixelY = 0.31f, renderDimensions = RENDER_DIMENSIONS)
	private val currentOffset = DlssJitterOffset(1, pixelX = 0.37f, pixelY = -0.21f, renderDimensions = RENDER_DIMENSIONS)

	/** Sample points spread across the frustum, from near the eye to the far plane. */
	private val probes = listOf(
		Vector3f(0f, 0f, -1f),
		Vector3f(3f, -2f, -12f),
		Vector3f(-6f, 4f, -80f),
		Vector3f(1f, 1f, -400f),
	)

	@Test
	fun `captures become predecessors only at the frame boundary`() {
		val state = ObjectMotionState()

		state.capture(7, 10.0, 64.0, 5.0)
		assertNull(state.previous(7), "nothing is published before the first frame boundary")
		assertNull(state.displacement(7))

		state.publish()
		assertEquals(position(10.0, 64.0, 5.0), state.previous(7))

		state.capture(7, 10.5, 64.0, 5.0)
		assertEquals(
			position(10.0, 64.0, 5.0),
			state.previous(7),
			"an in-flight capture must not move the predecessor before the boundary",
		)
		assertEquals(vec(0.5f, 0f, 0f), state.displacement(7))

		state.publish()
		assertEquals(position(10.5, 64.0, 5.0), state.previous(7))
		assertNull(state.displacement(7), "after the boundary the in-flight set is empty again")
	}

	@Test
	fun `an object first observed this frame has no predecessor to reproject against`() {
		val state = ObjectMotionState()

		state.capture(7, 10.0, 64.0, 5.0)
		assertNull(state.previous(7), "the first observation has no predecessor")
		assertNull(state.displacement(7))
		state.publish()

		// The second observation has a predecessor: the first frame's published capture.
		state.capture(7, 10.5, 64.0, 5.0)
		assertEquals(position(10.0, 64.0, 5.0), state.previous(7))
		assertEquals(vec(0.5f, 0f, 0f), state.displacement(7))
	}

	@Test
	fun `each object's predecessor is keyed by its own stable id`() {
		val state = ObjectMotionState()
		state.capture(1, 10.0, 64.0, 5.0)
		state.capture(2, 20.0, 70.0, 6.0)
		state.publish()
		state.capture(1, 10.5, 64.0, 5.0)
		state.capture(2, 20.5, 70.0, 6.0)
		state.publish()

		state.capture(1, 11.0, 64.0, 5.0)
		state.capture(2, 20.0, 70.0, 6.0)

		assertEquals(position(10.5, 64.0, 5.0), state.previous(1))
		assertEquals(position(20.5, 70.0, 6.0), state.previous(2))
		assertEquals(vec(0.5f, 0f, 0f), state.displacement(1))
		assertEquals(vec(-0.5f, 0f, 0f), state.displacement(2))
	}

	@Test
	fun `an object absent from a frame loses its predecessor at the boundary`() {
		val state = ObjectMotionState()
		state.capture(1, 10.0, 64.0, 5.0)
		state.publish()
		state.capture(1, 10.5, 64.0, 5.0)
		state.publish()

		// Frame 3: object 1 is gone, only object 2 is captured. The writer draws only living
		// objects, so the absent object's pixels never run; what must hold is that it has no
		// displacement (no current observation to pair with) and that the boundary evicts it.
		state.capture(2, 30.0, 64.0, 7.0)
		assertNull(state.displacement(1), "an absent object has no displacement to compose")
		assertEquals(
			position(10.5, 64.0, 5.0),
			state.previous(1),
			"the predecessor is evicted at the boundary, not before it",
		)

		state.publish()
		assertNull(state.previous(1), "an id absent from the frame is evicted at the boundary")
		assertEquals(position(30.0, 64.0, 7.0), state.previous(2))
	}

	@Test
	fun `an id reused after despawn does not inherit the dead object's predecessor`() {
		val state = ObjectMotionState()
		state.capture(1, 10.0, 64.0, 5.0)
		state.publish()
		state.capture(1, 10.5, 64.0, 5.0)
		state.publish()

		// Object 1 despawns; the boundary evicts its id while only object 2 is captured.
		state.capture(2, 30.0, 64.0, 7.0)
		state.publish()

		// Minecraft reuses entity ids, so a new object may take over id 1 anywhere in the world.
		state.capture(1, 100.0, 70.0, 3.0)
		assertNull(state.previous(1), "a reused id must start fresh, not reproject from the despawned object")
		assertNull(state.displacement(1))
	}

	@Test
	fun `reset forgets every predecessor and the next observation starts fresh`() {
		val state = ObjectMotionState()
		state.capture(1, 10.0, 64.0, 5.0)
		state.publish()
		state.capture(1, 10.5, 64.0, 5.0)

		state.reset()
		assertNull(state.previous(1))
		assertNull(state.displacement(1))

		state.capture(1, 11.0, 64.0, 5.0)
		assertNull(state.previous(1), "after a reset the next observation is a first observation")
		assertNull(state.displacement(1))
	}

	@Test
	fun `a world-still object keeps the camera reprojection exactly`() {
		val previous = sample()
		val current = sample(x = 0.5)
		val (frame, _, currentViewProjection) = cameraFrame(previous, current)

		val stillObject = objectReprojection(
			camera = frame,
			currentViewProjection = currentViewProjection,
			jitter = currentOffset,
			objectDelta = Vector3f(),
		)

		// The strongest form of the invariant: with no object motion the composition must come
		// back to the camera's own published reprojection, or the two halves have drifted apart.
		assertMatrixEquals(frame.reprojection, stillObject)
		for (probe in probes) {
			val cameraMotion = motionOf(frame.reprojection, probe)
			val actual = motionOf(stillObject, probe)
			assertEquals(cameraMotion.x, actual.x, tolerance, "x motion at $probe")
			assertEquals(cameraMotion.y, actual.y, tolerance, "y motion at $probe")
		}
	}

	@Test
	fun `the published frame reprojection is authoritative, never rebuilt from parallel inputs`() {
		// A reprojection no view-projection pair could produce, unrelated to the current
		// view-projection and jitter passed alongside it. A zero object displacement must come
		// back as exactly this matrix; that holds only if the camera's published reprojection
		// is the input, not if the composition rebuilt camera motion from parallel values.
		val supplied = Matrix4f().rotateX(0.35f).translate(2f, -1f, 0.5f)
		val frame = DlssFrameMotion(
			reprojection = Matrix4f(supplied),
			motionScaleX = RENDER_DIMENSIONS.width / 2f,
			motionScaleY = RENDER_DIMENSIONS.height / 2f,
			frameTimeMillis = 16f,
			reset = false,
		)
		val result = objectReprojection(
			camera = frame,
			currentViewProjection = Matrix4f().setPerspective(
				Math.toRadians(90.0).toFloat(),
				2f,
				10f,
				0.01f,
				true,
			),
			jitter = currentOffset,
			objectDelta = Vector3f(),
		)
		assertMatrixEquals(supplied, result)

		// And it is a copy, not an alias: mutating the result cannot rewrite the frame's matrix.
		val before = FloatArray(16)
		frame.reprojection.get(before)
		result.translate(1f, 0f, 0f)
		val after = FloatArray(16)
		frame.reprojection.get(after)
		assertTrue(before.contentEquals(after), "mutating the returned matrix must not touch the frame's")
	}

	@Test
	fun `a world-still object follows a rotating camera through both view transforms`() {
		val previousView = Matrix4f().rotateY(0.10f)
		val currentView = Matrix4f().rotateY(0.13f)
		val (frame, previousViewProjection, currentViewProjection) =
			cameraFrame(sample(view = previousView), sample(view = currentView))

		val stillObject = objectReprojection(
			camera = frame,
			currentViewProjection = currentViewProjection,
			jitter = currentOffset,
			objectDelta = Vector3f(),
		)

		for (probe in probes) {
			val expected = ndc(previousViewProjection, probe).sub(ndc(currentViewProjection, probe))
			val actual = motionOf(stillObject, probe, view = currentView)
			assertEquals(expected.x, actual.x, tolerance, "x motion at $probe")
			assertEquals(expected.y, actual.y, tolerance, "y motion at $probe")
		}
	}

	@Test
	fun `a translating object contributes its exact camera-relative NDC motion`() {
		val (frame, previousViewProjection, currentViewProjection) = cameraFrame(sample(), sample())
		val objectDelta = Vector3f(1.0f, 0.5f, 0f)

		val objectMotion = objectReprojection(
			camera = frame,
			currentViewProjection = currentViewProjection,
			jitter = currentOffset,
			objectDelta = objectDelta,
		)

		for (probe in probes) {
			// The object moved +x +0.5y in world space, so camera-relative it sat that much
			// further along in the previous frame; its pixels shift the opposite way.
			val expected = ndc(previousViewProjection, Vector3f(probe).sub(objectDelta))
				.sub(ndc(currentViewProjection, probe))
			val actual = motionOf(objectMotion, probe)
			assertEquals(expected.x, actual.x, tolerance, "x motion at $probe")
			assertEquals(expected.y, actual.y, tolerance, "y motion at $probe")
		}
		// The object must actually move the image, or the loop above also passes on a reprojection
		// that reports nothing at all.
		assertTrue(abs(motionOf(objectMotion, probes[1]).x) > tolerance)
	}

	@Test
	fun `an object's own motion composes against a moving camera`() {
		val previous = sample()
		val current = sample(x = 0.5)
		val (frame, previousViewProjection, currentViewProjection) = cameraFrame(previous, current)
		val objectDelta = Vector3f(0.25f, 0f, 0f)

		val objectMotion = objectReprojection(
			camera = frame,
			currentViewProjection = currentViewProjection,
			jitter = currentOffset,
			objectDelta = objectDelta,
		)

		for (probe in probes) {
			// Camera-relative displacement: +0.5 of camera minus +0.25 of object.
			val expected = ndc(previousViewProjection, Vector3f(probe).add(0.25f, 0f, 0f))
				.sub(ndc(currentViewProjection, probe))
			val actual = motionOf(objectMotion, probe)
			assertEquals(expected.x, actual.x, tolerance, "x motion at $probe")
			assertEquals(expected.y, actual.y, tolerance, "y motion at $probe")
		}
	}

	@Test
	fun `object reprojection leaves reversed-Z depth alone`() {
		val (frame, _, currentViewProjection) = cameraFrame(sample(), sample())
		val objectMotion = objectReprojection(
			camera = frame,
			currentViewProjection = currentViewProjection,
			jitter = currentOffset,
			objectDelta = Vector3f(1.0f, 0f, 0f),
		)

		// Reversed-Z: nearer geometry produces the larger depth value.
		val near = ndc(currentViewProjection, probes.first()).z
		val far = ndc(currentViewProjection, probes.last()).z
		assertTrue(near > far, "expected reversed-Z depth, got $near then $far")

		for (probe in probes) {
			val clip = clipOf(probe)
			val reprojected = objectMotion.transform(Vector4f(clip))
			assertTrue(reprojected.w > 0f, "point at $probe reprojected behind the eye")
			val depth = reprojected.z / reprojected.w
			assertTrue(depth in 0f..1f, "depth at $probe left [0,1]: $depth")
		}
	}

	@Test
	fun `a reset camera frame composes no object displacement and reports the identity`() {
		val motion = DlssCameraMotion(RENDER_DIMENSIONS)
		val first = motion.advance(sample(), currentOffset, 0L)
		assertTrue(first.reset)

		assertEquals(
			Matrix4f(),
			objectReprojection(
				camera = first,
				currentViewProjection = Matrix4f(),
				jitter = currentOffset,
				objectDelta = Vector3f(1f, 2f, 3f),
			),
			"a reset frame must not reproject against a camera DLSS never saw",
		)

		// A break in the chain behaves the same way: the frame after a lost frame is a reset frame.
		motion.advance(sample(), previousOffset, 0L)
		motion.reset()
		val next = motion.advance(sample(x = 4.0), currentOffset, 16_000_000L)
		assertTrue(next.reset)
		assertEquals(
			Matrix4f(),
			objectReprojection(
				camera = next,
				currentViewProjection = Matrix4f(),
				jitter = currentOffset,
				objectDelta = Vector3f(1f, 2f, 3f),
			),
		)
	}

	@Test
	fun `state displacement drives the full object reprojection end to end`() {
		// Nontrivial camera: translated and rotated between frames, differently jittered, so
		// camera motion cannot silently cancel object motion or jitter.
		val previousView = Matrix4f().rotateY(0.10f).rotateX(-0.04f)
		val currentView = Matrix4f().rotateY(0.13f).rotateX(0.06f)
		val previous = sample(x = 10.0, y = 62.0, z = -8.0, view = previousView)
		val current = sample(x = 12.0, y = 63.0, z = -8.5, view = currentView)
		val (frame, previousViewProjection, currentViewProjection) = cameraFrame(previous, current)
		val camDelta = cameraDelta(current, previous)

		// The object moves in all three world axes between frames; the exact delta flows
		// through the state buffer - the production capture seam - not a hand-built fixture.
		val state = ObjectMotionState()
		state.capture(7, 100.0, 64.0, 20.0)
		state.publish()
		state.capture(7, 102.5, 66.25, 22.75)
		val objectDelta = requireNotNull(state.displacement(7)) { "the object must have a predecessor and a current capture" }
		assertEquals(vec(2.5f, 2.25f, 2.75f), objectDelta)

		val objectMotion = objectReprojection(
			camera = frame,
			currentViewProjection = currentViewProjection,
			jitter = currentOffset,
			objectDelta = objectDelta,
		)

		for (probe in probes) {
			// Independent derivation from where the surface point actually sat, not from the
			// composition's algebra: camera-relative this frame it is at `probe`, and last
			// frame it was at `probe + cameraDelta - objectDelta` (the object moved
			// `objectDelta` in world space while the camera moved `camDelta`). Projecting the
			// two positions through their own frames' view-projections and subtracting is the
			// expected motion; jitter is exercised by the rendered clip and cancels in the
			// difference exactly as it must.
			val expected = ndc(previousViewProjection, Vector3f(probe).add(camDelta).sub(objectDelta))
				.sub(ndc(currentViewProjection, probe))
			val actual = motionOf(objectMotion, probe, view = currentView)
			assertEquals(expected.x, actual.x, tolerance, "x motion at $probe")
			assertEquals(expected.y, actual.y, tolerance, "y motion at $probe")
		}
		// The object's motion must actually move the image, or the comparison above also
		// passes on a reprojection that reports nothing at all.
		assertTrue(abs(motionOf(objectMotion, probes[1], view = currentView).x) > tolerance)
	}

	private fun sample(
		x: Double = 0.0,
		y: Double = 64.0,
		z: Double = 0.0,
		view: Matrix4f = Matrix4f(),
	) = DlssCameraSample(projection, view, x, y, z)

	private fun cameraDelta(current: DlssCameraSample, previous: DlssCameraSample) = Vector3f(
		(current.cameraX - previous.cameraX).toFloat(),
		(current.cameraY - previous.cameraY).toFloat(),
		(current.cameraZ - previous.cameraZ).toFloat(),
	)

	/**
	 * Runs a real camera advance and hands back the published motion plus the two unjittered
	 * view-projections it was composed from - the same pieces [objectReprojection] consumes, so
	 * the two compositions are compared on identical inputs rather than on parallel fixtures.
	 */
	private fun cameraFrame(
		previous: DlssCameraSample,
		current: DlssCameraSample,
	): Triple<DlssFrameMotion, Matrix4f, Matrix4f> {
		val motion = DlssCameraMotion(RENDER_DIMENSIONS)
		motion.advance(previous, previousOffset, 0L)
		val frame = motion.advance(current, currentOffset, 16_000_000L)
		return Triple(
			frame,
			Matrix4f(previous.projection).mul(previous.viewRotation),
			Matrix4f(current.projection).mul(current.viewRotation),
		)
	}

	/**
	 * Object motion the way the evaluation reads it: the normalized-device difference between
	 * where a point reprojects to and where the rendered - therefore jittered - frame put it.
	 */
	private fun motionOf(reprojection: Matrix4f, probe: Vector3f, view: Matrix4f = Matrix4f()): Vector4f {
		val clip = clipOf(probe, view)
		val reprojected = reprojection.transform(Vector4f(clip))
		return Vector4f(
			reprojected.x / reprojected.w - clip.x / clip.w,
			reprojected.y / reprojected.w - clip.y / clip.w,
			0f,
			1f,
		)
	}

	/** Where the jittered world projection actually put [probe] this frame. */
	private fun clipOf(probe: Vector3f, view: Matrix4f = Matrix4f()): Vector4f {
		val jittered = DlssProjectionJitter.apply(Matrix4f(projection).mul(view), currentOffset, Matrix4f())
		return jittered.transform(Vector4f(probe.x, probe.y, probe.z, 1f))
	}

	private fun ndc(transform: Matrix4f, probe: Vector3f): Vector4f {
		val clip = transform.transform(Vector4f(probe.x, probe.y, probe.z, 1f))
		return Vector4f(clip.x / clip.w, clip.y / clip.w, clip.z / clip.w, 1f)
	}

	private fun assertMatrixEquals(expected: Matrix4f, actual: Matrix4f) {
		val expectedArray = FloatArray(16)
		val actualArray = FloatArray(16)
		expected.get(expectedArray)
		actual.get(actualArray)
		for (index in expectedArray.indices) {
			assertEquals(expectedArray[index], actualArray[index], tolerance, "matrix component $index")
		}
	}

	private fun position(x: Double, y: Double, z: Double) = ObjectPosition(x, y, z)

	private fun vec(x: Float, y: Float, z: Float) = Vector3f(x, y, z)
}
