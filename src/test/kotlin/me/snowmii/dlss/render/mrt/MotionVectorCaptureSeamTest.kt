package me.snowmii.dlss.render.mrt

import me.snowmii.dlss.render.RenderRuntime
import me.snowmii.dlss.render.WorldPhase
import me.snowmii.dlss.render.mrt.ObjectPosition
import org.joml.Vector3f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Entity capture seam: the visible-entity extraction pass feeds each entity's interpolated render
 * position into the frame-boundary object-motion history the dynamic velocity writers will read.
 *
 * Minecraft 26.2 extracts entities in `GameRenderer.extract` -> `LevelExtractor.extract` ->
 * `extractVisibleEntities`, which runs *before* `LevelRenderer.render` HEAD opens the DLSS world
 * phase. At that add point the loop holds both halves of the capture: the live `Entity`
 * (`entity.getId()`, the stable key) and the `EntityRenderState` it just extracted (whose
 * `x/y/z` doubles are the partial-tick interpolated pose the geometry will be drawn at). The
 * capture seam must therefore land while the phase is *closed* - unlike pipeline observation,
 * which is open-phase-only - and only a world-phase completion whose DLSS evaluation/composition
 * succeeds publishes exactly once. False, skipped, or throwing evaluation, vanilla frames,
 * abandoned phases, world changes, releases, and close reset without retaining captures.
 *
 * The first eligible frame of a session can capture nothing: extraction precedes the render loop
 * building the phase, so the first frame's extraction finds no phase yet. That frame must publish
 * an empty boundary and stay reset - a first observation, never stale history.
 */
class MotionVectorCaptureSeamTest {
	private val mainTarget = fakeMainTarget()

	@Test
	fun `the extraction capture lands before the phase opens and publishes the exact interpolated pose under the entity id`() {
		val runtime = velocityRuntime()
		val phase = worldPhase(runtime)
		assertFalse(phase.isOpen, "extraction runs before the world phase opens")

		// The extraction pass fires while the phase is closed, so the seam must not gate on the
		// open phase the way pipeline observation does - the captures have to land before open.
		phase.captureEntity(7, 10.0, 64.0, 5.0)
		phase.captureEntity(9, -3.5, 72.25, 11.125)
		assertNull(runtime.objectMotion.previousPosition(7), "nothing is a predecessor before the frame boundary")

		renderDlssFrame(phase)

		assertEquals(position(10.0, 64.0, 5.0), runtime.objectMotion.previousPosition(7))
		assertEquals(position(-3.5, 72.25, 11.125), runtime.objectMotion.previousPosition(9))
	}

	@Test
	fun `a successful DLSS frame publishes its captures exactly once at the frame boundary`() {
		val runtime = velocityRuntime()
		val phase = worldPhase(runtime)

		phase.captureEntity(7, 10.0, 64.0, 5.0)
		renderDlssFrame(phase)
		assertEquals(position(10.0, 64.0, 5.0), runtime.objectMotion.previousPosition(7))

		// Second frame: the published frame is the predecessor the draw path composes from, and
		// it stays readable while the phase is open - between capture and publish.
		phase.captureEntity(7, 10.5, 64.0, 5.0)
		assertEquals(Vector3f(0.5f, 0f, 0f), runtime.objectMotion.objectDisplacement(7))
		phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = cameraSample())
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		assertEquals(
			Vector3f(0.5f, 0f, 0f),
			runtime.objectMotion.objectDisplacement(7),
			"the draw path reads this frame's displacement while the phase is open",
		)
		phase.end()
		assertEquals(position(10.5, 64.0, 5.0), runtime.objectMotion.previousPosition(7))
		assertNull(runtime.objectMotion.objectDisplacement(7), "the in-flight set is empty between frames")

		// A re-entrant end is a no-op, so the frame cannot publish twice.
		phase.end()
		assertEquals(position(10.5, 64.0, 5.0), runtime.objectMotion.previousPosition(7))
	}

	@Test
	fun `a false evaluation resets captures without publishing them`() {
		val runtime = velocityRuntime()
		val phase = worldPhase(runtime) { false }

		phase.captureEntity(7, 10.0, 64.0, 5.0)
		renderDlssFrame(phase)

		assertNull(runtime.objectMotion.previousPosition(7), "an uncomposed frame cannot become a predecessor")
		assertNull(runtime.objectMotion.objectDisplacement(7))
	}

	@Test
	fun `a skipped evaluation resets captures without publishing them`() {
		val runtime = velocityRuntime()
		var evaluations = 0
		val phase = worldPhase(runtime) {
			evaluations++
			true
		}

		phase.captureEntity(7, 10.0, 64.0, 5.0)
		// No camera sample means WorldPhase.evaluate skips its callback: no DLSS composition
		// happened even though the frame routed through the scene target.
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		phase.end()

		assertEquals(0, evaluations)
		assertNull(runtime.objectMotion.previousPosition(7), "a skipped evaluation cannot publish captures")
	}

	@Test
	fun `a throwing evaluation resets captures before propagating the failure`() {
		val runtime = velocityRuntime()
		val phase = worldPhase(runtime) { throw IllegalStateException("evaluation failed") }

		phase.captureEntity(7, 10.0, 64.0, 5.0)
		phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = cameraSample())
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		assertThrows(IllegalStateException::class.java) { phase.end() }

		assertFalse(phase.isOpen)
		assertNull(runtime.objectMotion.previousPosition(7), "a throwing evaluation cannot publish captures")
		assertNull(runtime.objectMotion.objectDisplacement(7))
	}

	@Test
	fun `a vanilla frame resets the object history without retaining captures`() {
		val runtime = velocityRuntime()
		val phase = worldPhase(runtime)

		phase.captureEntity(7, 10.0, 64.0, 5.0)
		renderDlssFrame(phase)
		assertEquals(position(10.0, 64.0, 5.0), runtime.objectMotion.previousPosition(7))

		// A vanilla frame (unsupported frame, panorama) breaks the accumulated history exactly
		// where the camera sequences break, so nothing survives into the next DLSS frame.
		phase.captureEntity(7, 11.0, 64.0, 5.0)
		phase.prepare(normalInWorldFrame = false, mainTarget = mainTarget, camera = cameraSample())
		phase.begin(normalInWorldFrame = false, mainTarget = mainTarget)
		phase.end()

		assertNull(runtime.objectMotion.previousPosition(7), "a vanilla frame must not retain the object history")
		assertNull(runtime.objectMotion.objectDisplacement(7))
	}

	@Test
	fun `an abandoned phase resets the object history`() {
		val runtime = velocityRuntime()
		val phase = worldPhase(runtime)

		phase.captureEntity(7, 10.0, 64.0, 5.0)
		phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = cameraSample())
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		// LevelRenderer.render throws: the tail never closes the phase, and the next frame's
		// prepare drops the abandoned phase and breaks the history it would otherwise leave.
		phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = cameraSample())

		assertNull(runtime.objectMotion.previousPosition(7))
		assertNull(runtime.objectMotion.objectDisplacement(7))
	}

	@Test
	fun `a world change resets the object history`() {
		val runtime = velocityRuntime()
		val phase = worldPhase(runtime)

		phase.captureEntity(7, 10.0, 64.0, 5.0)
		renderDlssFrame(phase)
		assertEquals(position(10.0, 64.0, 5.0), runtime.objectMotion.previousPosition(7))

		// setLevel / clearClientLevel: the scene is replaced, so the accumulated poses describe
		// a world that is gone and a reused entity id must not reproject against it.
		phase.resetHistory()

		assertNull(runtime.objectMotion.previousPosition(7))
		assertNull(runtime.objectMotion.objectDisplacement(7))
	}

	@Test
	fun `releasing the frame state resets the object history`() {
		val runtime = velocityRuntime()
		val phase = worldPhase(runtime)

		phase.captureEntity(7, 10.0, 64.0, 5.0)
		renderDlssFrame(phase)
		assertEquals(position(10.0, 64.0, 5.0), runtime.objectMotion.previousPosition(7))

		// Switching DLSS off releases the held targets and breaks the history: the frames that
		// come back are not continuous with the ones that stopped.
		runtime.setDlssEnabled(false)

		assertNull(runtime.objectMotion.previousPosition(7), "a released runtime must not retain the object history")
		assertNull(runtime.objectMotion.objectDisplacement(7))
	}

	@Test
	fun `closing the runtime resets the object history`() {
		val runtime = velocityRuntime()
		val phase = worldPhase(runtime)

		phase.captureEntity(7, 10.0, 64.0, 5.0)
		renderDlssFrame(phase)
		assertEquals(position(10.0, 64.0, 5.0), runtime.objectMotion.previousPosition(7))

		phase.close()

		assertNull(runtime.objectMotion.previousPosition(7), "a closed runtime must not retain the object history")
		assertNull(runtime.objectMotion.objectDisplacement(7))
	}

	@Test
	fun `the first eligible frame captures nothing and stays reset, never stale`() {
		// The first DLSS frame's extraction runs before the render loop has built the phase, so
		// no capture lands; the frame must publish an empty boundary rather than carry history
		// from a session that never rendered.
		val runtime = velocityRuntime()
		val phase = worldPhase(runtime)

		renderDlssFrame(phase)

		assertNull(runtime.objectMotion.previousPosition(7))
		assertNull(runtime.objectMotion.objectDisplacement(7))
	}

	private fun worldPhase(runtime: RenderRuntime, evaluate: () -> Boolean = { true }) = WorldPhase(
		runtime = runtime,
		present = { _, _ -> },
		onWorldTargetChanged = {},
		evaluateFrame = { _, _, _, _, _, _, _ -> evaluate() },
	)

	private fun renderDlssFrame(phase: WorldPhase) {
		phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = cameraSample())
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		phase.end()
	}

	private fun position(x: Double, y: Double, z: Double) = ObjectPosition(x, y, z)
}
