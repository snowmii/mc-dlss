package me.snowmii.dlss

import me.snowmii.dlss.streamline.LifecycleAdapter
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.streamline.ImageBinding
import me.snowmii.streamline.StreamlineSession
import me.snowmii.streamline.StreamlineSessionTestDouble
import me.snowmii.streamline.Vec2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class DlssResourceAbiTest {
	@Test
	fun `adapter stamps configured dimensions onto the request`() {
		var evaluated: EvaluationRequest? = null
		val native = object : StreamlineSessionTestDouble() {
			override fun initialize(
				vkInstance: Long,
				vkPhysicalDevice: Long,
				vkDevice: Long,
				sdkPath: Path,
				dataPath: Path,
			) = StreamlineSession.SUCCESS_RESULT

			override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int) =
				Dimensions(1280, 720)

			override fun configureSuperResolution(
				outputWidth: Int,
				outputHeight: Int,
				renderWidth: Int,
				renderHeight: Int,
				qualityMode: Int,
				renderPreset: Int,
			) = StreamlineSession.SUCCESS_RESULT

			override fun evaluateSuperResolution(request: EvaluationRequest): Int {
				evaluated = request
				return StreamlineSession.SUCCESS_RESULT
			}
		}
		val outputDimensions = Dimensions(2560, 1440)
		val adapter = LifecycleAdapter(DlssSession(config(outputDimensions)), native)
		val request = request().build()

		assertEquals(
			Dimensions(1280, 720),
			adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")),
		)
		assertTrue(adapter.evaluate(request))
		assertEquals(request().renderDimensions(Dimensions(1280, 720)).build(), evaluated)
	}

	private fun request() = EvaluationRequest.builder()
		.commandBuffer(101L)
		.color(ImageBinding(201L, 202L, 203))
		.depth(ImageBinding(301L, 302L, 303))
		.jitter(Vec2(0.25f, -0.5f))
		.motionScale(Vec2(1.25f, 1.5f))
		.frameTimeMilliseconds(16.7f)
		.resetHistory(true)

	private fun config(outputDimensions: Dimensions) = DlssStartupConfig(
		enabled = true,
		qualityMode = SRMode.QUALITY,
		outputDimensions = outputDimensions,
		sdkPath = null,
		nativeLibraryPath = null,
		dataPath = null,
		warnings = emptyList(),
	)
}
