package me.snowmii.dlss

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DlssFeatureLifecycleTest {
	// Newlines are normalized because these assertions match the source text literally, and a
	// Windows checkout hands the same file back with CRLF.
	private val source = Files.readString(Path.of("native", "mc_dlss.cpp")).replace("\r\n", "\n")

	@Test
	fun featureCreationIsDeferredToEvaluationCommandBuffer() {
		val configure = source.substringAfter("mc_dlss_configure").substringBefore("mc_dlss_evaluate")
		val evaluate = source.substringAfter("mc_dlss_evaluate")
		assertTrue(!configure.contains("NGX_VULKAN_CREATE_DLSS_EXT"))
		assertTrue(evaluate.contains("NGX_VULKAN_CREATE_DLSS_EXT(\n                from_uint64<VkCommandBuffer>(command_buffer)"))
		assertTrue(evaluate.indexOf("NGX_VULKAN_CREATE_DLSS_EXT") < evaluate.indexOf("NGX_VULKAN_EVALUATE_DLSS_EXT"))
	}

	@Test
	fun configurationMismatchReleasesAndRecreatesFeatureBeforeEvaluation() {
		assertTrue(source.contains("featureMatchesConfiguration"))
		assertTrue(source.contains("g_state.featureQualityMode == g_state.qualityMode"))
		val mismatch = source.substringAfter("if (!featureMatchesConfiguration)").substringBefore("NVSDK_NGX_VK_DLSS_Eval_Params")
		assertTrue(mismatch.indexOf("release_feature()") < mismatch.indexOf("NGX_VULKAN_CREATE_DLSS_EXT"))
		assertTrue(source.contains("NVSDK_NGX_DLSS_Feature_Flags_DepthInverted"))
	}

	@Test
	fun evaluationCarriesRequiredInputsAndLifecycleReleasesOwnership() {
		listOf(
			"Feature.pInColor = &colorResource",
			"Feature.pInOutput = &outputResource",
			"pInDepth = &depthResource",
			"pInMotionVectors = &motionResource",
			"InRenderSubrectDimensions = {render_width, render_height}",
			"InFrameTimeDeltaInMsec = frame_time_milliseconds",
		).forEach { assertTrue(source.contains(it), "Missing evaluation input: $it") }
		val shutdown = source.substringAfter("int32_t shutdown_state()").substringBefore("int32_t cleanup_after_initialize_failure")
		assertTrue(shutdown.indexOf("release_feature()") < shutdown.indexOf("destroy_capability_parameters()"))
		val reset = source.substringAfter("mc_dlss_reset").substringBefore("mc_dlss_close")
		assertTrue(reset.contains("release_feature()"))
	}
}
