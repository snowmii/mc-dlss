package me.snowmii.dlss.render
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.SRMode
import me.snowmii.streamline.Dimensions

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A replaced scene is a discontinuity the frames themselves cannot show.
 *
 * A world load, a dimension change, and a disconnect can leave the camera exactly where it stood
 * while every surface in the frame becomes a different one, so the camera-displacement test that
 * catches a teleport sees nothing at all. Minecraft's own level swap is the only signal, and this
 * covers what it has to do to the phase: break the accumulated history, restart the jitter
 * sequence, and drop a frame that was prepared against the world being left.
 */
class LevelChangeResetTest {
	private val output = Dimensions(2560, 1440)
	private val render = Dimensions(1280, 720)
	private val tolerance = 1e-4f

	private val mainTarget = FakeTarget(output.width, output.height)

	@Test
	fun `a level change breaks the history the next frame would reproject against`() {
		val runtime = readyRuntime()
		val phase = phase(runtime)

		phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = sample())
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		phase.end()
		phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = sample())
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		assertFalse(runtime.activeMotion!!.reset, "an ordinary second frame accumulates")
		phase.end()

		phase.resetHistory()

		phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = sample())
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		val firstOfNewLevel = runtime.activeMotion!!
		assertTrue(firstOfNewLevel.reset, "the first frame of a new level has nothing to reproject against")
		assertEquals(Matrix4f(), firstOfNewLevel.reprojection)
		assertEquals(0f, firstOfNewLevel.frameTimeMillis, tolerance)
		phase.end()
	}

	@Test
	fun `a level change restarts the jitter sequence`() {
		val runtime = readyRuntime()
		val phase = phase(runtime)

		phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = sample())
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		phase.end()
		phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = sample())
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		assertEquals(1, runtime.activeJitter!!.index)
		phase.end()

		phase.resetHistory()

		phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = sample())
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		assertEquals(0, runtime.activeJitter!!.index, "the new scene starts the sequence again")
		phase.end()
	}

	@Test
	fun `a level change drops a phase prepared against the world being left`() {
		val runtime = readyRuntime()
		val phase = phase(runtime)

		// The projection seam ran for a frame LevelRenderer.render never reached, and the level was
		// swapped in between: the prepared phase belongs to the world that is gone.
		phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = sample())
		phase.resetHistory()

		phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = sample())
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		assertTrue(runtime.activeMotion!!.reset, "the dropped preparation must not survive the swap")
		assertEquals(0, runtime.activeJitter!!.index, "nor may the sequence it advanced")
		phase.end()
	}

	@Test
	fun `resetting an untouched phase is harmless`() {
		val runtime = readyRuntime()
		val phase = phase(runtime)

		// Minecraft loads a level long before the first world frame, so this is the ordinary case.
		phase.resetHistory()

		phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = sample())
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		assertTrue(runtime.activeMotion!!.reset)
		assertEquals(0, runtime.activeJitter!!.index)
		phase.end()
	}

	private fun sample() = DlssCameraSample(
		projection = Matrix4f().perspective(1.2f, 16f / 9f, 0.05f, 1000f),
		viewRotation = Matrix4f(),
		cameraX = 0.0,
		cameraY = 64.0,
		cameraZ = 0.0,
	)

	private fun phase(runtime: RenderRuntime) = WorldPhase(
		runtime = runtime,
		present = { _, _ -> },
		onWorldTargetChanged = {},
	)

	private fun readyRuntime(): RenderRuntime {
		val session = DlssSession(
			DlssStartupConfig(
				enabled = true,
				qualityMode = SRMode.QUALITY,
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

	/** Render target with no GPU buffers, so the phase is testable off the render thread. */
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
