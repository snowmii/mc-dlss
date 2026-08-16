package me.snowmii.dlss.render
import me.snowmii.dlss.nativeSource
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.SRMode
import me.snowmii.streamline.Dimensions

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * M-9's rung: camera-only motion, reversed-Z depth semantics, and deterministic jitter are
 * produced coherently for world rendering and for NGX.
 *
 * The two halves have to agree or DLSS collapses silently. The world is rendered through a
 * jittered projection, so a pixel's clip position carries this frame's offset; the motion vector
 * NGX reads must not, because NGX is told the jitter separately and would otherwise count it
 * twice. Every assertion below therefore reads motion the way the evaluation will -
 * `ndc(reprojection * clip) - ndc(clip)` against clip positions taken from the *jittered*
 * projection - so any leak between the two shows up as motion where there is none.
 *
 * The projection is built the way Minecraft 26.2 builds the world projection: `Projection.getMatrix`
 * passes `near = zFar, far = zNear` into JOML with zero-to-one depth, which is what makes the
 * effort's depth reversed.
 */
class MotionJitterTest {
	private val output = Dimensions(2560, 1440)
	private val render = Dimensions(1280, 720)
	private val tolerance = 1e-4f

	private val projection: Matrix4f = Matrix4f().setPerspective(
		Math.toRadians(70.0).toFloat(),
		render.width.toFloat() / render.height,
		1000f,
		0.05f,
		true,
	)

	/** The two frames jitter differently, so neither offset can quietly cancel the other. */
	private val previousOffset = DlssJitterOffset(0, pixelX = -0.44f, pixelY = 0.31f, renderDimensions = render)
	private val currentOffset = DlssJitterOffset(1, pixelX = 0.37f, pixelY = -0.21f, renderDimensions = render)

	/** Sample points spread across the frustum, from near the eye to the far plane. */
	private val probes = listOf(
		Vector3f(0f, 0f, -1f),
		Vector3f(3f, -2f, -12f),
		Vector3f(-6f, 4f, -80f),
		Vector3f(1f, 1f, -400f),
	)

	@Test
	fun `a still camera produces no motion at any depth, however the jitter moved`() {
		val motion = DlssCameraMotion(render)

		motion.advance(sample(), previousOffset, nanos(0))
		val frame = motion.advance(sample(), currentOffset, nanos(16))

		assertFalse(frame.reset)
		for (probe in probes) {
			val vector = motionOf(frame, probe)
			assertEquals(0f, vector.x, tolerance, "x motion at $probe")
			assertEquals(0f, vector.y, tolerance, "y motion at $probe")
		}
	}

	@Test
	fun `clipToPrevClip maps unjittered clip to the previous frame's unjittered clip`() {
		val motion = DlssCameraMotion(render)
		val previousView = Matrix4f().rotateY(0.10f)
		val currentView = Matrix4f().rotateY(0.13f)

		motion.advance(sample(x = 0.0, view = previousView), previousOffset, nanos(0))
		val frame = motion.advance(sample(x = 0.5, view = currentView), currentOffset, nanos(16))

		// Streamline requires clipToPrevClip and forbids temporal-AA jitter in any of its
		// matrices, so the payload maps *unjittered* clip to unjittered clip: a point's current
		// clip position must land where the previous frame's own projection put it, camera-relative.
		// The motion pass's reprojection is this conjugated by the jitter and would fail here.
		val current = Matrix4f(projection).mul(currentView)
		val previous = Matrix4f(projection).mul(previousView)
		for (probe in probes) {
			val clip = current.transform(Vector4f(probe.x, probe.y, probe.z, 1f))
			val mapped = frame.clipToPrevClip.transform(Vector4f(clip))
			// Camera-relative: the camera moved +0.5 along x, so the point sat that much
			// further along x measured from where the camera used to be.
			val expected = ndc(previous, Vector3f(probe).add(0.5f, 0f, 0f))
			assertEquals(expected.x, mapped.x / mapped.w, tolerance, "x at $probe")
			assertEquals(expected.y, mapped.y / mapped.w, tolerance, "y at $probe")
			assertEquals(expected.z, mapped.z / mapped.w, tolerance, "z at $probe")
		}
	}

	@Test
	fun `a reset frame reports the identity camera step`() {
		val motion = DlssCameraMotion(render)

		val frame = motion.advance(sample(), currentOffset, nanos(0))

		assertTrue(frame.reset)
		assertEquals(Matrix4f(), frame.clipToPrevClip, "no predecessor means no camera step")
	}

	@Test
	fun `a camera that slid sideways moves every pixel by the reprojected difference`() {
		val motion = DlssCameraMotion(render)

		motion.advance(sample(x = 0.0), previousOffset, nanos(0))
		val frame = motion.advance(sample(x = 0.5), currentOffset, nanos(16))

		for (probe in probes) {
			// Camera-relative: the camera moved +0.5 along x, so the same world point sat half a
			// block further along x when measured from where the camera used to be.
			val expected = ndc(projection, Vector3f(probe).add(0.5f, 0f, 0f)).sub(ndc(projection, probe))
			val actual = motionOf(frame, probe)
			assertEquals(expected.x, actual.x, tolerance, "x motion at $probe")
			assertEquals(expected.y, actual.y, tolerance, "y motion at $probe")
		}
		// A sideways camera must actually move the image, or the loop above would also pass on a
		// reprojection that reported nothing at all.
		assertTrue(abs(motionOf(frame, probes[1]).x) > tolerance)
	}

	@Test
	fun `a camera that rotated moves pixels by the difference of the two view transforms`() {
		val motion = DlssCameraMotion(render)
		val previousView = Matrix4f().rotateY(0.10f)
		val currentView = Matrix4f().rotateY(0.13f)

		motion.advance(sample(view = previousView), previousOffset, nanos(0))
		val frame = motion.advance(sample(view = currentView), currentOffset, nanos(16))

		for (probe in probes) {
			val expected = ndc(Matrix4f(projection).mul(previousView), probe)
				.sub(ndc(Matrix4f(projection).mul(currentView), probe))
			val actual = motionOf(frame, probe, view = currentView)
			assertEquals(expected.x, actual.x, tolerance, "x motion at $probe")
			assertEquals(expected.y, actual.y, tolerance, "y motion at $probe")
		}
	}

	@Test
	fun `reprojection leaves reversed-Z depth alone`() {
		val motion = DlssCameraMotion(render)
		motion.advance(sample(), previousOffset, nanos(0))
		val frame = motion.advance(sample(z = -0.25), currentOffset, nanos(16))

		// Reversed-Z: nearer geometry produces the larger depth value.
		val near = ndc(projection, probes.first()).z
		val far = ndc(projection, probes.last()).z
		assertTrue(near > far, "expected reversed-Z depth, got $near then $far")

		for (probe in probes) {
			val clip = clipOf(probe)
			val reprojected = frame.reprojection.transform(Vector4f(clip))
			// The camera moved, so depth changes - but it stays a reversed-Z depth in front of
			// the eye rather than being flipped or driven behind it by the reprojection itself.
			assertTrue(reprojected.w > 0f, "point at $probe reprojected behind the eye")
			val depth = reprojected.z / reprojected.w
			assertTrue(depth in 0f..1f, "depth at $probe left [0,1]: $depth")
		}
		// A camera that moved toward the geometry must report it nearer, which in reversed-Z
		// means a larger depth value than the frame it is reprojected from.
		val probe = probes[1]
		val clip = clipOf(probe)
		val reprojected = frame.reprojection.transform(Vector4f(clip))
		assertTrue(
			reprojected.z / reprojected.w < clip.z / clip.w,
			"a camera that moved closer must reproject to a smaller reversed-Z depth",
		)
	}

	@Test
	fun `the first DLSS frame resets history and reports no interval`() {
		val motion = DlssCameraMotion(render)

		val first = motion.advance(sample(), currentOffset, nanos(0))

		assertTrue(first.reset)
		assertEquals(0f, first.frameTimeMillis)
		assertEquals(Matrix4f(), first.reprojection)
	}

	@Test
	fun `frame time is the interval since the previous DLSS frame`() {
		val motion = DlssCameraMotion(render)

		motion.advance(sample(), currentOffset, nanos(0))
		val second = motion.advance(sample(), currentOffset, nanos(16))
		val third = motion.advance(sample(), currentOffset, nanos(41))

		assertEquals(16f, second.frameTimeMillis, tolerance)
		assertEquals(25f, third.frameTimeMillis, tolerance)
	}

	@Test
	fun `motion scale converts normalized-device motion to render pixels`() {
		val motion = DlssCameraMotion(render)

		val frame = motion.advance(sample(), currentOffset, nanos(0))

		assertEquals(render.width / 2f, frame.motionScaleX)
		assertEquals(render.height / 2f, frame.motionScaleY)
	}

	@Test
	fun `Streamline converts NDC motion to pixels once`() {
		val source = nativeSource("internal/sl_dlss.cpp")

		// Streamline multiplies this normalized scale by render width/height before handing it
		// to NGX. NDC spans two units edge-to-edge, so half converts NDC displacement to pixels.
		assertTrue(source.contains("constants.mvecScale = sl::float2(0.5f, 0.5f);"))
	}

	@Test
	fun `a broken chain resets rather than reprojecting against a camera DLSS never saw`() {
		val motion = DlssCameraMotion(render)

		motion.advance(sample(), previousOffset, nanos(0))
		motion.reset()
		val next = motion.advance(sample(x = 4.0), currentOffset, nanos(16))

		assertTrue(next.reset)
		assertEquals(Matrix4f(), next.reprojection)
	}

	@Test
	fun `the runtime publishes one motion per eligible world phase and none for a vanilla frame`() {
		val runtime = readyRuntime()

		runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = output, camera = sample())
		val first = runtime.activeMotion
		runtime.endWorldPhase()
		assertNull(runtime.activeMotion)

		runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = output, camera = sample())
		val second = runtime.activeMotion
		runtime.endWorldPhase()

		assertNotNull(first)
		assertTrue(first!!.reset)
		assertFalse(second!!.reset)
		assertEquals(render.width / 2f, second.motionScaleX)
		assertEquals(16f, second.frameTimeMillis, tolerance)

		runtime.beginWorldPhase(normalInWorldFrame = false, outputDimensions = output, camera = sample())
		assertNull(runtime.activeMotion)
		runtime.endWorldPhase()
	}

	@Test
	fun `a vanilla frame between DLSS frames resets both the jitter sequence and the history`() {
		val runtime = readyRuntime()

		runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = output, camera = sample())
		runtime.endWorldPhase()
		runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = output, camera = sample())
		val beforeBreak = runtime.activeJitter
		runtime.endWorldPhase()

		runtime.beginWorldPhase(normalInWorldFrame = false, outputDimensions = output, camera = sample())
		runtime.endWorldPhase()

		runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = output, camera = sample())
		val afterBreak = runtime.activeJitter
		val motion = runtime.activeMotion
		runtime.endWorldPhase()

		assertEquals(1, beforeBreak!!.index)
		assertEquals(0, afterBreak!!.index)
		assertTrue(motion!!.reset, "history must not survive a frame DLSS never accumulated")
	}

	@Test
	fun `an eligible frame routed without a camera publishes no motion and breaks the chain`() {
		val runtime = readyRuntime()

		runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = output, camera = sample())
		runtime.endWorldPhase()
		runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = output, camera = null)
		assertNull(runtime.activeMotion)
		runtime.endWorldPhase()

		runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = output, camera = sample())
		assertTrue(runtime.activeMotion!!.reset)
		runtime.endWorldPhase()
	}

	@Test
	fun `a frame abandoned before it rendered leaves no camera for the next frame to measure from`() {
		val runtime = readyRuntime()
		val phase = WorldPhase(runtime = runtime, present = { _, _ -> }, onWorldTargetChanged = {})
		val mainTarget = FakeTarget(output.width, output.height)

		phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = sample())
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		phase.end()

		// renderLevel threw between the projection upload and LevelRenderer.render: this frame
		// decided a route and moved the predecessor, but produced no image.
		phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = sample(x = 3.0))

		phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = sample(x = 3.0))
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)

		assertTrue(
			runtime.activeMotion!!.reset,
			"history must not survive a frame that was never accumulated",
		)
		phase.end()
	}

	private fun sample(
		x: Double = 0.0,
		y: Double = 64.0,
		z: Double = 0.0,
		view: Matrix4f = Matrix4f(),
	) = DlssCameraSample(projection, view, x, y, z)

	private fun nanos(millis: Long) = millis * 1_000_000L

	/**
	 * Camera motion the way the evaluation reads it: the normalized-device difference between
	 * where a point reprojects to and where the rendered - therefore jittered - frame put it.
	 */
	private fun motionOf(frame: DlssFrameMotion, probe: Vector3f, view: Matrix4f = Matrix4f()): Vector4f {
		val clip = clipOf(probe, view)
		val reprojected = frame.reprojection.transform(Vector4f(clip))
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

	private fun readyRuntime(): RenderRuntime {
		val session = DlssSession(
			DlssStartupConfig(
				enabled = true,
				qualityMode = SRMode.PERFORMANCE,
				outputDimensions = output,
				sdkPath = null,
				nativeLibraryPath = null,
				dataPath = null,
				warnings = emptyList(),
			),
		)
		var now = 0L
		return RenderRuntime(
			session = session,
			sceneTarget = SceneTarget(
				allocate = { width, height -> FakeTarget(width, height) },
				release = {},
			),
			startup = {
				check(session.markReadyAfterNativeStartup())
				render
			},
			clock = {
				now += 16_000_000L
				now
			},
		)
	}

	/** Render target with no GPU buffers, so the runtime is testable off the render thread. */
	private class FakeTarget(width: Int, height: Int) : RenderTarget("fake", true, GpuFormat.RGBA8_UNORM) {
		init {
			this.width = width
			this.height = height
		}

		override fun createBuffers(width: Int, height: Int) {
			this.width = width
			this.height = height
		}

		override fun destroyBuffers() = Unit
	}
}
