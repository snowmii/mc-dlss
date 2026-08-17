package me.snowmii.dlss.fg

import java.nio.file.Path
import me.snowmii.streamline.StreamlineSessionTestDouble
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.EvaluationImages
import me.snowmii.streamline.FrameTimings
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.streamline.MotionRequest
import me.snowmii.streamline.StreamlineSession
import me.snowmii.streamline.PresentTarget
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

class FgEvaluationTest {

	@Test
	fun `adapter gates present handoff on READY and latches failures`() {
		val native = FakeNative()
		val outputDimensions = Dimensions(2560, 1440)
		val session = DlssSession(config(outputDimensions))
		val adapter = LifecycleAdapter(session, native)

		// Not ready yet: the handoff must not reach the bridge.
		assertFalse(adapter.presentHandoff(), "a session that is not READY must not hand off")
		assertEquals(0, native.presentHandoffCalls)

		// Ready: initialize arms the session, and the handoff crosses to the bridge.
		assertTrue(
			adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")) != null,
			"initialize must bring the session to READY",
		)
		assertTrue(adapter.presentHandoff(), "a READY session must record the present handoff")
		assertEquals(1, native.presentHandoffCalls)

		// A refused handoff latches the session under the present-handoff stage, exactly like
		// any other native stage: a frame that cannot hand off must not present as if it could.
		native.presentHandoffResult = StreamlineSession.SUCCESS_RESULT + 1
		assertFalse(adapter.presentHandoff(), "a refused present handoff must latch the session")
		assertEquals(DlssSessionState.FALLBACK_LATCHED, session.state)
		assertEquals(DlssNativeStage.PRESENT_HANDOFF, session.failure?.stage)
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
	 * Records the present-handoff seam and answers the three calls [LifecycleAdapter.initialize]
	 * drives; everything else is a call this test never makes.
	 */
	private class FakeNative : StreamlineSessionTestDouble() {
		var presentHandoffResult = StreamlineSession.SUCCESS_RESULT
		var presentHandoffCalls = 0

		override fun recordPresentHandoff(): Int {
			presentHandoffCalls++
			return presentHandoffResult
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

		override fun acquireImages(): EvaluationImages = error("unexpected acquireImages")
		override fun releaseImages(): Int = error("unexpected releaseImages")
		override fun waitDeviceIdle(): Int = error("unexpected waitDeviceIdle")
		override fun frameTimings(): FrameTimings = error("unexpected frameTimings")
		override fun writeMotion(request: MotionRequest): Int = error("unexpected writeMotion")
		override fun presentOutput(target: PresentTarget): Int = error("unexpected presentOutput")
		override fun evaluateSuperResolution(request: EvaluationRequest): Int = error("unexpected evaluate")
	}

}
