package me.snowmii.dlss.session

import me.snowmii.dlss.nativeSource
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DlssFeatureLifecycleTest {
	private val api = nativeSource("mc_dlss_api.cpp")
	private val ngx = nativeSource("internal/ngx.cpp")
	private val session = nativeSource("internal/session.cpp")

	@Test
	fun featureCreationIsDeferredToEvaluationCommandBuffer() {
		// Configure stores dimensions and creates nothing; the feature is created by the
		// evaluation, on the command buffer the caller is recording into.
		val configure = api.substringAfter("mc_dlss_configure").substringBefore("mc_dlss_acquire_images")
		assertTrue(!configure.contains("ensure_feature"))
		assertTrue(!api.contains("NGX_VULKAN_CREATE_DLSS_EXT"))
		assertTrue(
			ngx.contains("NGX_VULKAN_CREATE_DLSS_EXT(\n        commandBuffer, 1, 1, &g_state.feature"),
			"the feature must be created on the command buffer the caller passed in",
		)

		val evaluate = api.substringAfter("mc_dlss_evaluate").substringBefore("mc_dlss_present_output")
		assertTrue(
			evaluate.indexOf("ensure_feature(recordingBuffer)") < evaluate.indexOf("record_evaluation("),
			"the feature has to exist before the evaluation that uses it is recorded",
		)
	}

	@Test
	fun configurationMismatchReleasesAndRecreatesFeatureBeforeEvaluation() {
		assertTrue(ngx.contains("featureMatchesConfiguration"))
		assertTrue(ngx.contains("g_state.featureQualityMode == g_state.qualityMode"))
		// A feature matching the configuration is kept; anything else is released before the
		// replacement is created, because NGX reads every creation input only at creation.
		val ensure = ngx.substringAfter("int32_t ensure_feature(").substringBefore("int32_t record_evaluation(")
		assertTrue(ensure.contains("if (featureMatchesConfiguration) {"))
		assertTrue(ensure.indexOf("release_feature()") < ensure.indexOf("NGX_VULKAN_CREATE_DLSS_EXT"))
		assertTrue(ngx.contains("NVSDK_NGX_DLSS_Feature_Flags_DepthInverted"))
	}

	@Test
	fun evaluationCarriesRequiredInputsAndLifecycleReleasesOwnership() {
		listOf(
			"Feature.pInColor = &colorResource",
			"Feature.pInOutput = &outputResource",
			"pInDepth = &depthResource",
			"pInMotionVectors = &motionResource",
			"InRenderSubrectDimensions = {info.render_width, info.render_height}",
			"InFrameTimeDeltaInMsec = info.frame_time_milliseconds",
		).forEach { assertTrue(ngx.contains(it), "Missing evaluation input: $it") }

		// Teardown order is the constraint: the feature belongs to the parameters, so it dies
		// first.
		val shutdown = session.substringAfter("int32_t shutdown_state()")
			.substringBefore("int32_t cleanup_after_initialize_failure")
		assertTrue(shutdown.indexOf("release_feature()") < shutdown.indexOf("destroy_capability_parameters()"))
		val reset = api.substringAfter("mc_dlss_reset").substringBefore("mc_dlss_close")
		assertTrue(reset.contains("release_feature()"))
	}
}
