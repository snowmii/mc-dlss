package me.snowmii.dlss.render

import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.session.SRMode
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.streamline.ImageBinding
import me.snowmii.streamline.StreamlineSession
import me.snowmii.streamline.StreamlineSessionTestDouble
import me.snowmii.streamline.SrTagRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DlssEvaluationBarrierTest {
	private val output = Dimensions(2560, 1440)
	private val render = Dimensions(1280, 720)

	@Test
	fun `evaluation keeps mod barriers on caller buffer across frames`(@TempDir dataPath: Path) {
		val native = RecordingNative()
		val session = DlssSession(
			DlssStartupConfig(
				enabled = true,
				qualityMode = SRMode.QUALITY,
				outputDimensions = output,
				sdkPath = dataPath,
				nativeLibraryPath = dataPath,
				dataPath = dataPath,
				warnings = emptyList(),
			),
		)
		val adapter = LifecycleAdapter(session, native)
		assertEquals(render, adapter.initialize(1L, 2L, 3L, dataPath, dataPath))

		val color = ImageBinding(0x102L, 0x101L, 37)
		val depth = ImageBinding(0x202L, 0x201L, 126)
		for (commandBuffer in longArrayOf(11L, 12L)) {
			assertTrue(adapter.tagSrResources(SrTagRequest(commandBuffer, color, depth)))
			assertTrue(
				adapter.evaluate(
					EvaluationRequest.builder()
						.commandBuffer(commandBuffer)
						.color(color)
						.depth(depth)
						.frameTimeMilliseconds(16.6f)
						.resetHistory(true)
						.build(),
				),
			)
		}

		assertEquals(listOf(11L, 12L), native.taggedBuffers)
		assertEquals(listOf(11L, 12L), native.evaluated.map(EvaluationRequest::commandBuffer))
		assertEquals(listOf(color, color), native.evaluated.map(EvaluationRequest::color))
		assertEquals(listOf(depth, depth), native.evaluated.map(EvaluationRequest::depth))
		assertEquals(listOf(render, render), native.evaluated.map(EvaluationRequest::renderDimensions))
	}

	private inner class RecordingNative : StreamlineSessionTestDouble() {
		val taggedBuffers = mutableListOf<Long>()
		val evaluated = mutableListOf<EvaluationRequest>()

		override fun initialize(vkInstance: Long, vkPhysicalDevice: Long, vkDevice: Long, sdkPath: Path, dataPath: Path) =
			StreamlineSession.SUCCESS_RESULT

		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int) = render

		override fun configureSuperResolution(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		) = StreamlineSession.SUCCESS_RESULT

		override fun tagSrResources(request: SrTagRequest): Int {
			taggedBuffers += request.commandBuffer
			return StreamlineSession.SUCCESS_RESULT
		}

		override fun evaluateSuperResolution(request: EvaluationRequest): Int {
			evaluated += request
			return StreamlineSession.SUCCESS_RESULT
		}
	}
}
