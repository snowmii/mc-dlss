package me.snowmii.dlss.fg

import java.nio.file.Path
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.FgTagRequest
import me.snowmii.streamline.ImageBinding
import me.snowmii.streamline.StreamlineSession
import me.snowmii.streamline.StreamlineSessionTestDouble
import me.snowmii.dlss.session.DlssNativeStage
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssSessionState
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.session.SRMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Verifies the mod-owned FG resource-tagging lifecycle seam. */
class FgResourceTaggingTest {

	@Test
	fun `adapter gates FG tags on READY and latches failures`() {
		val native = FakeNative()
		val outputDimensions = Dimensions(2560, 1440)
		val session = DlssSession(config(outputDimensions))
		val adapter = LifecycleAdapter(session, native)
		val request = FgTagRequest(
			101L,
			ImageBinding(301L, 302L, 303),
			ImageBinding(401L, 402L, 403),
			ImageBinding(501L, 502L, 503),
		)

		assertFalse(adapter.tagFgResources(request), "a session that is not READY must not tag")
		assertEquals(0, native.tagFgResourcesCalls)

		assertTrue(
			adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")) != null,
			"initialize must bring the session to READY",
		)
		assertTrue(adapter.tagFgResources(request), "a READY session must tag the frame's resources")
		assertEquals(listOf(request), native.tagFgResourcesRequests)

		native.tagFgResourcesResult = StreamlineSession.SUCCESS_RESULT + 1
		assertFalse(adapter.tagFgResources(request), "a refused FG tag must latch the session")
		assertEquals(DlssSessionState.FALLBACK_LATCHED, session.state)
		assertEquals(DlssNativeStage.TAG, session.failure?.stage)
	}

	private fun config(outputDimensions: Dimensions) = DlssStartupConfig(
		enabled = true,
		qualityMode = SRMode.QUALITY,
		outputDimensions = outputDimensions,
		sdkPath = null,
		nativeLibraryPath = null,
		dataPath = null,
		warnings = emptyList(),
	)

	private class FakeNative : StreamlineSessionTestDouble() {
		var tagFgResourcesResult = StreamlineSession.SUCCESS_RESULT
		var tagFgResourcesCalls = 0
		val tagFgResourcesRequests = mutableListOf<FgTagRequest>()

		override fun tagFrameGenerationResources(request: FgTagRequest): Int {
			tagFgResourcesCalls++
			tagFgResourcesRequests += request
			return tagFgResourcesResult
		}

		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		): Int = StreamlineSession.SUCCESS_RESULT

		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int) =
			Dimensions(1280, 720)

		override fun configureSuperResolution(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		): Int = StreamlineSession.SUCCESS_RESULT
	}
}
