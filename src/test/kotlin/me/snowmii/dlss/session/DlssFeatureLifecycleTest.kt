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
		// Configure stores dimensions and records the SL options; the evaluation records the SL
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
		assertTrue(sl.contains("slEvaluateFeature(sl::kFeatureDLSS, *frameToken, inputs, 1, commandBuffer)"))
		assertTrue(sl.contains("slSetTagForFrame(*frameToken, sl::ViewportHandle{0}, tags, numTags, commandBuffer)"))
		assertTrue(!sl.contains("&commandBuffer"), "SL must receive the VkCommandBuffer handle, not its stack address")
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
	fun closeAndResetReleaseOnlyModuleOwnedResources() {
		// Teardown order is the constraint: the module's GPU objects die before the device
		// that owns them, the retained Streamline frame token goes with the state, and close
		// then shuts the Streamline runtime down (while the caller's device is still alive)
		// before resetting the bootstrap/proxy/session bookkeeping. The direct-NGX feature,
		// capability parameters, and shutdown are retired: close releases only module-owned
		// Vulkan resources, Streamline frame state, and the Streamline runtime, never NGX
		// ownership.
		val shutdown = session.substringAfter("int32_t shutdown_state()")
			.substringBefore("} // namespace mc_dlss")
		assertTrue(shutdown.contains("destroy_timing()"))
		assertTrue(shutdown.contains("destroy_motion_pass()"))
		assertTrue(shutdown.contains("release_images()"))
		assertTrue(shutdown.contains("shutdown_streamline()"))
		assertTrue(shutdown.contains("reset_state()"))
		assertTrue(
			shutdown.indexOf("destroy_timing()") < shutdown.indexOf("destroy_motion_pass()"),
			"the motion pass must die after the timing objects",
		)
		assertTrue(
			shutdown.indexOf("destroy_motion_pass()") < shutdown.indexOf("release_images()"),
			"the images must die after the pass that writes them",
		)
		assertTrue(
			shutdown.indexOf("release_images()") < shutdown.indexOf("reset_state()"),
			"the retained frame token must drop with the state, after the resources",
		)
		assertTrue(!shutdown.contains("release_feature"))
		assertTrue(!shutdown.contains("DestroyParameters"))
		assertTrue(!shutdown.contains("Shutdown1"))
		assertTrue(!shutdown.contains("NVSDK_NGX_VULKAN_"))

		// Reset releases the module-owned images and the retained frame token and keeps the
		// session ready; it owns no NGX object to release.
		val reset = api.substringAfter("mc_dlss_reset").substringBefore("mc_dlss_close")
		assertTrue(reset.contains("release_images()"))
		assertTrue(reset.contains("g_state.frameToken = nullptr"))
		assertTrue(!reset.contains("release_feature"))

		// Close hands the whole session to the same module-owned teardown.
		val close = api.substringAfter("mc_dlss_close")
		assertTrue(close.contains("shutdown_state()"))
	}
}
