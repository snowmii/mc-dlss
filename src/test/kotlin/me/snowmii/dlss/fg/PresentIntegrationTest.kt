package me.snowmii.dlss.fg

import java.nio.file.Path
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssSessionState
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.session.SRMode
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.NativeApi
import me.snowmii.streamline.NativeApiTestDouble
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Present boundary wiring: production delegates handoff to its native seam only while ready,
 * and latches a refused handoff instead of allowing a frame to present as eligible.
 *
 * Live Streamline composition belongs to focused streamline tests. Frame composition belongs to
 * [FgFrameCompositionTest]; this test keeps only mod-owned lifecycle behavior.
 */
class PresentIntegrationTest {

	@Test
	fun `ready session delegates present handoff and latches refusal`() {
		val native = RecordingNativeApi()
		val session = DlssSession(
			DlssStartupConfig(
				enabled = true,
				qualityMode = SRMode.QUALITY,
				outputDimensions = Dimensions(2560, 1440),
				sdkPath = null,
				nativeLibraryPath = null,
				dataPath = null,
				warnings = emptyList(),
			),
		)
		val adapter = LifecycleAdapter(session, native)

		assertEquals(Dimensions(1280, 720), adapter.initialize(1L, 2L, 3L, Path.of("."), Path.of(".")))
		assertTrue(adapter.presentHandoff())
		assertEquals(1, native.handoffs)

		native.handoffResult = FAIL_INVALID_PARAMETER
		assertTrue(!adapter.presentHandoff())
		assertEquals(DlssSessionState.FALLBACK_LATCHED, session.state)
		assertEquals(2, native.handoffs)
	}

	private class RecordingNativeApi : NativeApiTestDouble() {
		var handoffs = 0
		var handoffResult = NativeApi.SUCCESS_RESULT

		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		): Int = NativeApi.SUCCESS_RESULT

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
		): Int = NativeApi.SUCCESS_RESULT

		override fun recordPresentHandoff(): Int {
			handoffs++
			return handoffResult
		}
	}

	private companion object {
		const val FAIL_INVALID_PARAMETER = 0xBAD00005.toInt()
	}
}
