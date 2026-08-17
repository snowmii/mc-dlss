package me.snowmii.dlss.fg

import java.nio.file.Path
import me.snowmii.streamline.StreamlineSessionTestDouble
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.StreamlineSession
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

class FgOptionsContractTest {

	@Test
	fun `adapter records FG options only when ready and latches failures`() {
		val native = FakeNative()
		val outputDimensions = Dimensions(2560, 1440)
		val session = DlssSession(config(outputDimensions))
		val adapter = LifecycleAdapter(session, native)

		// Not ready yet: the record must not reach the bridge.
		assertFalse(adapter.configureFg(3), "a session that is not READY must not record FG options")
		assertEquals(0, native.configureFgCalls)

		// Ready: initialize arms the session, and the record passes the back-buffer count
		// through to the bridge.
		assertTrue(
			adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")) != null,
			"initialize must bring the session to READY",
		)
		assertTrue(adapter.configureFg(3), "a READY session must record the FG options")
		assertEquals(listOf(3), native.configureFgValues)

		// A refused record latches the session under the configure stage, exactly like any
		// other native stage.
		native.configureFgResult = StreamlineSession.SUCCESS_RESULT + 1
		assertFalse(adapter.configureFg(4), "a refused FG record must latch the session")
		assertEquals(DlssSessionState.FALLBACK_LATCHED, session.state)
		assertEquals(DlssNativeStage.CONFIGURE, session.failure?.stage)
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

	/**
	 * Records the FG-option seam and answers the three calls [LifecycleAdapter.initialize]
	 * drives; everything else is a call this test never makes.
	 */
	private class FakeNative : StreamlineSessionTestDouble() {
		var configureFgResult = StreamlineSession.SUCCESS_RESULT
		var configureFgCalls = 0
		val configureFgValues = mutableListOf<Int>()

		override fun configureFg(numBackBuffers: Int): Int {
			configureFgCalls++
			configureFgValues += numBackBuffers
			return configureFgResult
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
