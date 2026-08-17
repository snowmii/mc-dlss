package me.snowmii.dlss.fg

import java.nio.file.Path
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.session.SRMode
import me.snowmii.streamline.CameraConstants
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.streamline.FgTagRequest
import me.snowmii.streamline.ImageBinding
import me.snowmii.streamline.StreamlineSession
import me.snowmii.streamline.StreamlineSessionTestDouble
import me.snowmii.streamline.Vec2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers mc-dlss's FG composition boundary without owning Streamline's live orientation proof.
 * Native image blits and FG viewport constants belong to native integration coverage; this test
 * keeps the mod-owned contract: FG tags and SR evaluation receive the same engine-space inputs.
 */
class FgImageOrientationTest {

	@Test
	fun `composition keeps SR camera and FG resources in engine orientation`() {
		val native = RecordingNativeApi()
		val adapter = LifecycleAdapter(
			DlssSession(
				DlssStartupConfig(
					enabled = true,
					qualityMode = SRMode.QUALITY,
					outputDimensions = Dimensions(2560, 1440),
					sdkPath = null,
					nativeLibraryPath = null,
					dataPath = null,
					warnings = emptyList(),
				),
			),
			native,
		)
		assertTrue(adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")) != null)
		assertTrue(adapter.configureFg(3))
		val camera = camera()
		val depth = ImageBinding(11L, 12L, 100)
		val hudless = ImageBinding(21L, 22L, 37)
		val ui = ImageBinding(31L, 32L, 37)

		assertTrue(adapter.tagFgResources(FgTagRequest(7L, depth, hudless, ui)))
		assertTrue(adapter.evaluate(
				EvaluationRequest.builder()
					.commandBuffer(7L)
					.color(ImageBinding(41L, 42L, 37))
					.depth(depth)
					.jitter(Vec2(0.25f, -0.5f))
					.motionScale(Vec2(1f, 1f))
					.frameTimeMilliseconds(16.6f)
					.resetHistory(true)
					.renderDimensions(Dimensions(1280, 720))
					.camera(camera)
					.build(),
			))

		assertEquals(listOf("initialize", "configureFg", "fgTag", "evaluate"), native.order)
		assertEquals(FgTagRequest(7L, depth, hudless, ui), native.fgTag)
		assertSame(camera, native.evaluation.camera)
		assertEquals(-0.5f, native.evaluation.jitter.y)
	}

	@Test
	fun `row mirror changes source row and only negates motion y`() {
		val source = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)
		val mirrored = mirrorRows(source, width = 1, channels = 3)
		assertTrue(mirrored.contentEquals(floatArrayOf(4f, 5f, 6f, 1f, 2f, 3f)))

		val motion = floatArrayOf(2f, 3f, 4f, 5f)
		assertTrue(mirrorMotion(motion, width = 1).contentEquals(floatArrayOf(4f, -5f, 2f, -3f)))
	}

	private fun mirrorRows(source: FloatArray, width: Int, channels: Int): FloatArray {
		val rows = source.size / (width * channels)
		return FloatArray(source.size) { index ->
			val row = index / (width * channels)
			val column = index % (width * channels)
			source[(rows - 1 - row) * width * channels + column]
		}
	}

	private fun mirrorMotion(source: FloatArray, width: Int): FloatArray {
		val mirrored = mirrorRows(source, width, 2)
		for (index in 1 until mirrored.size step 2) mirrored[index] = -mirrored[index]
		return mirrored
	}

	private fun camera() = CameraConstants(
		FloatArray(16) { it.toFloat() }, FloatArray(16) { (it + 16).toFloat() },
		floatArrayOf(1f, 2f, 3f), floatArrayOf(1f, 0f, 0f),
		floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, -1f),
		FloatArray(16) { if (it % 5 == 0) 1f else 0f },
		FloatArray(16) { if (it % 5 == 0) 1f else 0f },
		0.05f, 1000f, 1f, 16f / 9f,
	)

	private class RecordingNativeApi : StreamlineSessionTestDouble() {
		val order = mutableListOf<String>()
		lateinit var fgTag: FgTagRequest
		lateinit var evaluation: EvaluationRequest

		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		): Int {
			order += "initialize"
			return StreamlineSession.SUCCESS_RESULT
		}

		override fun queryOptimalDimensions(
			outputWidth: Int,
			outputHeight: Int,
			qualityMode: Int,
		): Dimensions = Dimensions(1280, 720)

		override fun configureSuperResolution(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		): Int = StreamlineSession.SUCCESS_RESULT

		override fun configureFg(numBackBuffers: Int): Int {
			order += "configureFg"
			return StreamlineSession.SUCCESS_RESULT
		}

		override fun tagFrameGenerationResources(request: FgTagRequest): Int {
			order += "fgTag"
			fgTag = request
			return StreamlineSession.SUCCESS_RESULT
		}

		override fun evaluateSuperResolution(request: EvaluationRequest): Int {
			order += "evaluate"
			evaluation = request
			return StreamlineSession.SUCCESS_RESULT
		}
	}
}
