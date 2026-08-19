package me.snowmii.dlss.render

import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.session.SRMode
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.ImageBinding
import me.snowmii.streamline.MotionRequest
import me.snowmii.streamline.StreamlineSession
import me.snowmii.streamline.StreamlineSessionTestDouble
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class DlssMotionVectorTest {
	@Test
	fun `camera motion keeps caller data and stamps configured render dimensions`() {
		val native = RecordingNative()
		val adapter = LifecycleAdapter(
			DlssSession(
				DlssStartupConfig(
					enabled = true,
					qualityMode = SRMode.QUALITY,
					outputDimensions = Dimensions(2560, 1440),
					sdkPath = Path.of("sdk"),
					nativeLibraryPath = null,
					dataPath = Path.of("data"),
					warnings = emptyList(),
				),
			),
			native,
		)
		assertTrue(adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")) != null)

		val request = MotionRequest(
			77L,
			ImageBinding(11L, 12L, 13),
			FloatArray(16) { it.toFloat() },
			Dimensions(1, 1),
		)

		assertTrue(adapter.writeMotion(request))
		assertEquals(
			MotionRequest(77L, ImageBinding(11L, 12L, 13), FloatArray(16) { it.toFloat() }, Dimensions(1280, 720)),
			native.lastRequest,
		)
	}

	private class RecordingNative : StreamlineSessionTestDouble() {
		var lastRequest: MotionRequest? = null

		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		): Int = StreamlineSession.SUCCESS_RESULT

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

		override fun writeMotion(request: MotionRequest): Int {
		lastRequest = request
		return StreamlineSession.SUCCESS_RESULT
	}
	}
}
