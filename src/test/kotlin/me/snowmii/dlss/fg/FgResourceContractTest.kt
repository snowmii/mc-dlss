package me.snowmii.dlss.fg

import java.nio.file.Path
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.session.SRMode
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.FgTagRequest
import me.snowmii.streamline.ImageBinding
import me.snowmii.streamline.StreamlineSession
import me.snowmii.streamline.StreamlineSessionTestDouble
import me.snowmii.streamline.SrTagRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FgResourceContractTest {

	@Test
	fun `ready adapter tags SR and FG resources on same command buffer`() {
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

		val commandBuffer = 77L
		val depth = ImageBinding(11L, 12L, 13)
		val color = ImageBinding(21L, 22L, 23)
		val hudless = ImageBinding(31L, 32L, 33)
		val ui = ImageBinding(41L, 42L, 43)
		val sr = SrTagRequest(commandBuffer, color, depth)
		val fg = FgTagRequest(commandBuffer, depth, hudless, ui)

		assertTrue(adapter.tagSrResources(sr))
		assertTrue(adapter.tagFgResources(fg))
		assertEquals(listOf("sr", "fg"), native.order)
		assertEquals(sr, native.srRequest)
		assertEquals(fg, native.fgRequest)
		assertEquals(commandBuffer, native.srRequest?.commandBuffer)
		assertEquals(commandBuffer, native.fgRequest?.commandBuffer)
	}

	private class RecordingNativeApi : StreamlineSessionTestDouble() {
		val order = mutableListOf<String>()
		var srRequest: SrTagRequest? = null
		var fgRequest: FgTagRequest? = null

		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		): Int = StreamlineSession.SUCCESS_RESULT

		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int): Dimensions =
			Dimensions(1280, 720)

		override fun configureSuperResolution(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		): Int = StreamlineSession.SUCCESS_RESULT

		override fun tagSrResources(request: SrTagRequest): Int {
			order += "sr"
			srRequest = request
			return StreamlineSession.SUCCESS_RESULT
		}

		override fun tagFrameGenerationResources(request: FgTagRequest): Int {
			order += "fg"
			fgRequest = request
			return StreamlineSession.SUCCESS_RESULT
		}
	}
}
