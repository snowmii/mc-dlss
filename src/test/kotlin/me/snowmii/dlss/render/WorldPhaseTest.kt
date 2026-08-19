package me.snowmii.dlss.render
import me.snowmii.dlss.DlssSession
import me.snowmii.dlss.DlssStartupConfig
import me.snowmii.dlss.SRMode
import me.snowmii.streamline.Dimensions

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorldPhaseTest {
	private val output = Dimensions(2560, 1440)
	private val render = Dimensions(1707, 960)
	private val mainTarget = HeadlessRenderTarget(output.width, output.height)

	private val presented = mutableListOf<Pair<RenderTarget, RenderTarget>>()
	private val evaluated = mutableListOf<Triple<RenderTarget, DlssJitterOffset, DlssFrameMotion>>()
	private val evaluatedCameras = mutableListOf<DlssCameraSample?>()
	private var targetChanges = 0
	private var presentedWhenEvaluated = -1
	private val evaluatedDestinations = mutableListOf<RenderTarget>()
	private var composes = false

	@Test
	fun `an eligible phase renders into a render-sized scene target and overrides the world target`() {
		val phase = phase(readyRuntime())

		val worldTarget = phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)

		assertTrue(phase.isOpen)
		assertEquals(render.width, worldTarget.width)
		assertEquals(render.height, worldTarget.height)
		assertSame(worldTarget, phase.worldTargetOverride)
	}

	@Test
	fun `the main target is never resized and is what every seam sees outside the phase`() {
		val phase = phase(readyRuntime())

		assertNull(phase.worldTargetOverride)
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		phase.end()

		assertFalse(phase.isOpen)
		assertNull(phase.worldTargetOverride)
		assertEquals(output.width, mainTarget.width)
		assertEquals(output.height, mainTarget.height)
		assertTrue(mainTarget.releases == 0)
	}

	@Test
	fun `closing an eligible phase presents the low-resolution scene into the main target`() {
		val phase = phase(readyRuntime())

		val worldTarget = phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		phase.end()

		assertEquals(listOf(worldTarget to mainTarget as RenderTarget), presented)
	}

	@Test
	fun `a vanilla frame renders straight into the main target and presents nothing`() {
		val phase = phase(readyRuntime())

		val worldTarget = phase.begin(normalInWorldFrame = false, mainTarget = mainTarget)
		assertNull(phase.worldTargetOverride)
		phase.end()

		assertSame(mainTarget, worldTarget)
		assertTrue(presented.isEmpty())
	}

	@Test
	fun `the sky renderer resets only when the resolved world target changes identity`() {
		val phase = phase(readyRuntime())

		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		phase.end()
		assertEquals(1, targetChanges)

		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		phase.end()
		assertEquals(1, targetChanges)

		phase.begin(normalInWorldFrame = false, mainTarget = mainTarget)
		phase.end()
		assertEquals(2, targetChanges)
	}

	@Test
	fun `a session without DLSS keeps every frame on the main target`() {
		val phase = phase(runtime(session(enabled = false)) { render })

		val worldTarget = phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		phase.end()

		assertSame(mainTarget, worldTarget)
		assertNull(phase.worldTargetOverride)
		assertTrue(presented.isEmpty())
	}

	@Test
	fun `a degenerate main target is never measured as output dimensions`() {
		val phase = phase(readyRuntime())
		val unsized = HeadlessRenderTarget(0, 0)

		val worldTarget = phase.begin(normalInWorldFrame = true, mainTarget = unsized)
		phase.end()

		assertSame(unsized, worldTarget)
		assertTrue(presented.isEmpty())
	}

	@Test
	fun `preparing decides the route and jitter without opening the phase`() {
		val phase = phase(readyRuntime())

		val offset = phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget)

		assertFalse(phase.isOpen)
		// The world target override must stay off until begin, or the hand, item, and screen
		// effects that render after the world would see the low-resolution target too.
		assertNull(phase.worldTargetOverride)
		assertEquals(render, offset!!.renderDimensions)
	}

	@Test
	fun `beginning consumes a matching preparation instead of routing again`() {
		val runtime = readyRuntime()
		val phase = phase(runtime)

		val prepared = phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget)
		val worldTarget = phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)

		assertTrue(phase.isOpen)
		assertSame(worldTarget, phase.worldTargetOverride)
		// A second route would have advanced the jitter sequence past the prepared phase.
		assertEquals(prepared, runtime.activeJitter)
	}

	@Test
	fun `beginning without a preparation still routes the frame itself`() {
		val runtime = readyRuntime()
		val phase = phase(runtime)

		val worldTarget = phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)

		assertEquals(render.width, worldTarget.width)
		assertEquals(render, runtime.activeJitter!!.renderDimensions)
	}

	@Test
	fun `a vanilla frame prepares no jitter`() {
		val phase = phase(readyRuntime())

		assertNull(phase.prepare(normalInWorldFrame = false, mainTarget = mainTarget))
	}

	@Test
	fun `a preparation the frame never rendered is discarded by the next one`() {
		val runtime = readyRuntime()
		val phase = phase(runtime)

		val abandoned = phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget)
		// No begin: renderLevel threw between the projection upload and LevelRenderer.render.
		val second = phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget)
		val worldTarget = phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)

		assertEquals(second, runtime.activeJitter)
		assertNotEquals(abandoned, second)
		phase.end()
		assertEquals(listOf(worldTarget to mainTarget as RenderTarget), presented)
	}

	@Test
	fun `a phase abandoned by a failed frame is discarded rather than crashing the next one`() {
		val phase = phase(readyRuntime())

		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		// No end(): LevelRenderer.render threw, so its tail never ran.
		val worldTarget = phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		phase.end()

		assertTrue(phase.isOpen.not())
		assertEquals(render.width, worldTarget.width)
		assertEquals(listOf(worldTarget to mainTarget as RenderTarget), presented)
	}

	@Test
	fun `an eligible frame is evaluated with the jitter and motion it rendered with, before it is presented`() {
		val phase = phase(readyRuntime())
		val preparedCamera = camera()

		val jitter = phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = preparedCamera)
		val worldTarget = phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		phase.end()

		val (evaluatedTarget, evaluatedJitter, evaluatedMotion) = evaluated.single()
		assertSame(worldTarget, evaluatedTarget)
		assertEquals(jitter, evaluatedJitter)
		assertTrue(evaluatedMotion.reset, "the first frame of a session has no history to reproject against")
		// The camera travels as the evaluation callback's own parameter: the phase snapshots the
		// seam's matrices when prepare stores the sample, and the evaluation reads the snapshot,
		// never the phase's field, which close clears before the evaluation runs.
		assertEquals(preparedCamera, evaluatedCameras.single())
		// DLSS reads the scene the world rendered; presenting first would hand it the frame after.
		assertEquals(0, presentedWhenEvaluated)
		assertEquals(1, presented.size)
	}

	@Test
	fun `a composed frame reaches the vanilla main target and is not blitted over`() {
		composes = true
		val phase = phase(readyRuntime())

		phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = camera())
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		phase.end()

		// The upscaled frame is already in the main target; the low-resolution blit would paint
		// over it with exactly what DLSS was there to replace.
		assertSame(mainTarget, evaluatedDestinations.single())
		assertTrue(presented.isEmpty())
	}

	@Test
	fun `a frame whose evaluation composed nothing still shows the low-resolution scene`() {
		composes = false
		val phase = phase(readyRuntime())

		phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = camera())
		val worldTarget = phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		phase.end()

		assertEquals(listOf(worldTarget to mainTarget as RenderTarget), presented)
	}

	@Test
	fun `the evaluation reads the camera as prepared, not as the renderer later rewrote it`() {
		val phase = phase(readyRuntime())
		val preparedCamera = camera()
		val preparedProjection = Matrix4f(preparedCamera.projection)
		val preparedRotation = Matrix4f(preparedCamera.viewRotation)

		phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = preparedCamera)
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		// Minecraft reuses the sample's matrices across frames: the seam's originals are
		// rewritten in place once the renderer moves on. The snapshot taken by prepare is what
		// the evaluation must read, not these live values.
		preparedCamera.projection.m30(99f).m31(99f).m32(99f)
		preparedCamera.viewRotation.m10(9f).m11(9f).m12(9f)
		phase.end()

		assertEquals(preparedProjection, evaluatedCameras.single()!!.projection)
		assertEquals(preparedRotation, evaluatedCameras.single()!!.viewRotation)
	}

	@Test
	fun `a vanilla frame is never evaluated`() {
		val phase = phase(readyRuntime())

		phase.begin(normalInWorldFrame = false, mainTarget = mainTarget)
		phase.end()

		assertTrue(evaluated.isEmpty())
	}

	@Test
	fun `an eligible frame the projection seam never sampled is not evaluated`() {
		val phase = phase(readyRuntime())

		// No camera means no motion, and a frame DLSS cannot reproject is worse than one it skips.
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		phase.end()

		assertTrue(evaluated.isEmpty())
		assertTrue(evaluatedCameras.isEmpty())
		assertEquals(1, presented.size)
	}

	private fun camera() = DlssCameraSample(
		projection = Matrix4f().perspective(1.2f, 16f / 9f, 0.05f, 1000f),
		viewRotation = Matrix4f(),
		cameraX = 0.0,
		cameraY = 0.0,
		cameraZ = 0.0,
	)

	private fun phase(runtime: RenderRuntime) = WorldPhase(
		runtime = runtime,
		present = { scene, main -> presented += scene to main },
		onWorldTargetChanged = { targetChanges++ },
		evaluateFrame = { rendered, destination, jitter, motion, _, _, camera ->
			presentedWhenEvaluated = presented.size
			evaluated += Triple(rendered, jitter, motion)
			evaluatedCameras += camera
			evaluatedDestinations += destination
			composes
		},
	)

	private fun readyRuntime(): RenderRuntime {
		val session = session(enabled = true)
		return runtime(session) {
			check(session.markReadyAfterNativeStartup())
			render
		}
	}

	private fun runtime(session: DlssSession, startup: () -> Dimensions?) = RenderRuntime(
		session = session,
		sceneTarget = SceneTarget(
			allocate = { width, height -> HeadlessRenderTarget(width, height) },
			release = { (it as HeadlessRenderTarget).releases++ },
		),
		startup = startup,
	)

	private fun session(enabled: Boolean) = DlssSession(
		DlssStartupConfig(
			enabled = enabled,
			qualityMode = SRMode.QUALITY,
			outputDimensions = output,
			sdkPath = null,
			nativeLibraryPath = null,
			dataPath = null,
			warnings = emptyList(),
		),
	)

	private class HeadlessRenderTarget(width: Int, height: Int) : RenderTarget("fake", true, GpuFormat.RGBA8_UNORM) {
		var releases = 0

		init {
			this.width = width
			this.height = height
		}

		override fun createBuffers(width: Int, height: Int) {
			this.width = width
			this.height = height
		}

		override fun destroyBuffers() {
			releases++
		}
	}
}
