package me.snowmii.dlss.session

import me.snowmii.dlss.nativeSource
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DlssFeatureLifecycleTest {
	private val api = nativeSource("mc_dlss_api.cpp")
	private val sl = nativeSource("internal/sl_dlss.cpp")
	private val session = nativeSource("internal/session.cpp")

	@Test
	fun evaluateRecordsTheSlEvaluationOnTheCallersCommandBuffer() {
		// Configure stores dimensions and creates no feature; the evaluation records the SL
		// constants and the feature evaluation on the command buffer the caller is recording
		// into, and no NGX feature creation exists anywhere in the API.
		val configure = api.substringAfter("mc_dlss_configure").substringBefore("mc_dlss_acquire_images")
		assertTrue(!configure.contains("slEvaluateFeature"))
		assertTrue(!configure.contains("ensure_feature"))
		assertTrue(!api.contains("NGX_VULKAN_CREATE_DLSS_EXT"))
		assertTrue(!api.contains("NGX_VULKAN_EVALUATE_DLSS_EXT"))

		val evaluate = api.substringAfter("mc_dlss_evaluate").substringBefore("mc_dlss_present_output")
		assertTrue(evaluate.contains("record_sr_evaluation(*info, recordingBuffer)"))
		assertTrue(
			evaluate.indexOf("record_layout_transition") < evaluate.indexOf("record_sr_evaluation("),
			"the inputs must be transitioned before the evaluation reads them",
		)
		// The evaluation records the frame's constants and the feature evaluation itself,
		// against the frame token the tag call retained, on the caller's command buffer.
		assertTrue(sl.contains("slSetConstants(constants, *frameToken, sl::ViewportHandle{0})"))
		assertTrue(sl.contains("slEvaluateFeature(sl::kFeatureDLSS, *frameToken, inputs, 1, &commandBuffer)"))
	}

	@Test
	fun theEvaluationConsumesTheFrameTokenTheTagRetained() {
		// The tag obtains the frame token only when none is retained for the frame, and the
		// evaluation consumes it: evaluating with no retained token fails, and the next frame's
		// tag obtains a fresh token.
		assertTrue(sl.contains("slGetNewFrameToken"))
		val tag = sl.substringAfter("int32_t tag_sr_resources(").substringBefore("} // namespace mc_dlss")
		assertTrue(
			tag.indexOf("frameToken == nullptr") < tag.indexOf("slGetNewFrameToken"),
			"the tag must not re-obtain a token one is already retained",
		)
		val evaluate = sl.substringAfter("int32_t record_sr_evaluation(").substringBefore("int32_t tag_sr_resources(")
		assertTrue(evaluate.contains("g_state.frameToken"))
		assertTrue(evaluate.contains("kNotInitialized"), "evaluating with no retained token must fail")
	}

	@Test
	fun lifecycleReleasesTheNgxFeatureBeforeItsParameters() {
		// Teardown order is the constraint: the feature belongs to the parameters, so it dies
		// first. The NGX feature surface stays until the retirement capability removes it.
		val shutdown = session.substringAfter("int32_t shutdown_state()")
			.substringBefore("int32_t cleanup_after_initialize_failure")
		assertTrue(shutdown.indexOf("release_feature()") < shutdown.indexOf("destroy_capability_parameters()"))
		val reset = api.substringAfter("mc_dlss_reset").substringBefore("mc_dlss_close")
		assertTrue(reset.contains("release_feature()"))
	}
}
