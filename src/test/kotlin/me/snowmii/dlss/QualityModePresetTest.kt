package me.snowmii.dlss

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Ultra Performance and DLAA are selectable, and every session runs a preset it named.
 *
 * Until this checkpoint the mod could ask NGX for three of the five modes it implements, and asked
 * for no preset at all - so the model that produced a frame was whichever one the installed DLL
 * happened to default to, which is exactly the fact AC-6's record has to state and no log line
 * could.
 */
class QualityModePresetTest {
	private val output = DlssDimensions(2560, 1440)

	// Newlines are normalized because these assertions match the source text literally, and a
	// Windows checkout hands the same file back with CRLF.
	private val nativeSource = Files.readString(Path.of("native", "mc_dlss.cpp")).replace("\r\n", "\n")

	@Test
	fun everyImplementedModeIsSelectableByProperty() {
		val selected = listOf(
			"quality" to DlssQualityMode.QUALITY,
			"balanced" to DlssQualityMode.BALANCED,
			"performance" to DlssQualityMode.PERFORMANCE,
			"ultra-performance" to DlssQualityMode.ULTRA_PERFORMANCE,
			"ultra-perf" to DlssQualityMode.ULTRA_PERFORMANCE,
			"dlaa" to DlssQualityMode.DLAA,
		)

		selected.forEach { (value, expected) ->
			val config = configFrom(DlssStartupConfig.MODE_PROPERTY to value)
			assertEquals(expected, config.qualityMode, "$value must select $expected")
			assertTrue(config.warnings.isEmpty(), "$value must not warn: ${config.warnings}")
		}
	}

	@Test
	fun modeCarriesTheNgxValueTheSdkDefinesForIt() {
		// NVSDK_NGX_PerfQuality_Value: MaxPerf 0, Balanced 1, MaxQuality 2, UltraPerformance 3,
		// UltraQuality 4 (defined, never implemented), DLAA 5.
		assertEquals(0, DlssQualityMode.PERFORMANCE.ngxValue)
		assertEquals(1, DlssQualityMode.BALANCED.ngxValue)
		assertEquals(2, DlssQualityMode.QUALITY.ngxValue)
		assertEquals(3, DlssQualityMode.ULTRA_PERFORMANCE.ngxValue)
		assertEquals(5, DlssQualityMode.DLAA.ngxValue)
		assertTrue(DlssQualityMode.entries.none { it.ngxValue == 4 }, "UltraQuality is not offered")
	}

	@Test
	fun everyModeDefaultsToTheDocumentedPresetForIt() {
		assertEquals(DlssRenderPreset.K, DlssQualityMode.DLAA.defaultPreset)
		assertEquals(DlssRenderPreset.K, DlssQualityMode.QUALITY.defaultPreset)
		assertEquals(DlssRenderPreset.K, DlssQualityMode.BALANCED.defaultPreset)
		assertEquals(DlssRenderPreset.M, DlssQualityMode.PERFORMANCE.defaultPreset)
		assertEquals(DlssRenderPreset.L, DlssQualityMode.ULTRA_PERFORMANCE.defaultPreset)

		DlssQualityMode.entries.forEach { mode ->
			val config = configFrom(DlssStartupConfig.MODE_PROPERTY to mode.propertyValue)
			assertEquals(mode.defaultPreset, config.renderPreset, "${mode.propertyValue} default preset")
		}
	}

	@Test
	fun presetPropertyOverridesTheModeDefaultAndDegradesToItWhenUnreadable() {
		val overridden = configFrom(
			DlssStartupConfig.MODE_PROPERTY to "performance",
			DlssStartupConfig.PRESET_PROPERTY to "j",
		)
		assertEquals(DlssRenderPreset.J, overridden.renderPreset)
		assertTrue(overridden.warnings.isEmpty())

		val explicitDefault = configFrom(
			DlssStartupConfig.MODE_PROPERTY to "performance",
			DlssStartupConfig.PRESET_PROPERTY to "default",
		)
		assertEquals(DlssRenderPreset.M, explicitDefault.renderPreset)

		// Preset E is deprecated and A through D were removed from the SDK, so naming one is a
		// value the mod refuses rather than forwards.
		val invalid = configFrom(
			DlssStartupConfig.MODE_PROPERTY to "ultra-performance",
			DlssStartupConfig.PRESET_PROPERTY to "e",
		)
		assertEquals(DlssRenderPreset.L, invalid.renderPreset, "degrades to the mode's own default")
		assertTrue(
			invalid.warnings.any { it.contains(DlssStartupConfig.PRESET_PROPERTY) && it.contains("e") },
			"invalid preset must warn: ${invalid.warnings}",
		)
	}

	@Test
	fun configuredModeAndPresetBothReachTheNativeBridge() {
		val session = DlssSession(
			DlssStartupConfig(
				enabled = true,
				qualityMode = DlssQualityMode.ULTRA_PERFORMANCE,
				renderPreset = DlssRenderPreset.J,
				outputDimensions = output,
				sdkPath = Path.of("sdk"),
				nativeLibraryPath = null,
				dataPath = Path.of("data"),
				warnings = emptyList(),
			),
		)
		val native = RecordingNative()

		assertNotNull(DlssLifecycleAdapter(session, native).initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")))
		assertEquals(DlssQualityMode.ULTRA_PERFORMANCE.ngxValue, native.queriedQualityMode)
		assertEquals(DlssQualityMode.ULTRA_PERFORMANCE.ngxValue, native.configuredQualityMode)
		assertEquals(DlssRenderPreset.J.ngxValue, native.configuredRenderPreset)
	}

	@Test
	fun nativeAcceptsEveryImplementedModeAndRefusesUltraQuality() {
		val validator = nativeSource
			.substringAfter("bool valid_quality_mode(")
			.substringBefore("const char* preset_parameter_for(")

		listOf(
			"NVSDK_NGX_PerfQuality_Value_MaxPerf",
			"NVSDK_NGX_PerfQuality_Value_Balanced",
			"NVSDK_NGX_PerfQuality_Value_MaxQuality",
			"NVSDK_NGX_PerfQuality_Value_UltraPerformance",
			"NVSDK_NGX_PerfQuality_Value_DLAA",
		).forEach { value ->
			assertTrue(validator.contains(value), "valid_quality_mode must accept $value")
		}
		assertTrue(
			!validator.contains("NVSDK_NGX_PerfQuality_Value_UltraQuality"),
			"UltraQuality is defined by NGX and never implemented, so it stays refused",
		)
	}

	@Test
	fun nativeWritesThePresetForTheRunningModeBeforeTheFeatureIsCreated() {
		listOf(
			"NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Performance",
			"NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Balanced",
			"NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Quality",
			"NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraPerformance",
			"NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_DLAA",
		).forEach { parameter ->
			assertTrue(nativeSource.contains(parameter), "every mode needs its own preset key: $parameter")
		}

		// NGX reads the hint at creation only, so writing it afterwards is writing it never.
		val creation = nativeSource
			.substringAfter("if (!featureMatchesConfiguration)")
			.substringBefore("NVSDK_NGX_VK_DLSS_Eval_Params")
		val presetWrite = creation.indexOf("NVSDK_NGX_Parameter_SetUI(g_state.capabilityParameters, presetParameter")
		val featureCreate = creation.indexOf("NGX_VULKAN_CREATE_DLSS_EXT")
		assertTrue(presetWrite >= 0, "the preset must be written on the creation path")
		assertTrue(presetWrite < featureCreate, "the preset must be written before the feature is created")
	}

	@Test
	fun dlaaRendersAtOutputResolutionWithoutAskingNgxForIt() {
		val query = nativeSource
			.substringAfter("int32_t query_optimal_dimensions(")
			.substringBefore("NVSDK_NGX_Parameter_DLSSOptimalSettingsCallback")
		assertTrue(
			query.contains("NVSDK_NGX_PerfQuality_Value_DLAA"),
			"DLAA is 1:1 by definition, so it must not depend on the optimal-settings callback",
		)
		assertTrue(
			query.contains("*renderWidth = outputWidth") && query.contains("*renderHeight = outputHeight"),
			"DLAA's render dimensions are its output dimensions",
		)
	}

	@Test
	fun changingOnlyThePresetRecreatesTheFeature() {
		assertTrue(
			nativeSource.contains("g_state.featureRenderPreset == g_state.renderPreset"),
			"a feature created under another preset does not match the configuration",
		)
		assertTrue(
			nativeSource.contains("g_state.featureRenderPreset = g_state.renderPreset"),
			"the created feature records the preset it was created under",
		)
	}

	private fun configFrom(vararg entries: Pair<String, String>): DlssStartupConfig {
		val properties = Properties()
		entries.forEach { (name, value) -> properties.setProperty(name, value) }
		return DlssStartupConfig.from(properties)
	}

	/** Records what the adapter passed down, and nothing else; every later stage is unreachable. */
	private class RecordingNative : DlssNativeApi {
		var queriedQualityMode: Int? = null
		var configuredQualityMode: Int? = null
		var configuredRenderPreset: Int? = null

		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		): Int = DlssNativeApi.SUCCESS_RESULT

		override fun queryOptimalDimensions(
			outputWidth: Int,
			outputHeight: Int,
			qualityMode: Int,
		): DlssDimensions {
			queriedQualityMode = qualityMode
			return DlssDimensions(853, 480)
		}

		override fun configure(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		): Int {
			configuredQualityMode = qualityMode
			configuredRenderPreset = renderPreset
			return DlssNativeApi.SUCCESS_RESULT
		}

		override fun acquireImages(): DlssEvaluationImages = throw UnsupportedOperationException()

		override fun releaseImages(): Int = throw UnsupportedOperationException()

		override fun waitDeviceIdle(): Int = DlssNativeApi.SUCCESS_RESULT

		override fun frameTimings(): DlssFrameTimings? = null

		override fun writeMotion(
			commandBuffer: Long,
			depthView: Long,
			depthImage: Long,
			depthFormat: Int,
			depthAspectMask: Int,
			depthBaseMipLevel: Int,
			depthLevelCount: Int,
			depthBaseArrayLayer: Int,
			depthLayerCount: Int,
			reprojection: FloatArray,
			renderWidth: Int,
			renderHeight: Int,
		): Int = throw UnsupportedOperationException()

		override fun presentOutput(
			commandBuffer: Long,
			destinationImage: Long,
			destinationAspectMask: Int,
			destinationBaseMipLevel: Int,
			destinationLevelCount: Int,
			destinationBaseArrayLayer: Int,
			destinationLayerCount: Int,
			destinationWidth: Int,
			destinationHeight: Int,
		): Int = throw UnsupportedOperationException()

		override fun evaluate(
			commandBuffer: Long,
			colorView: Long,
			colorImage: Long,
			colorFormat: Int,
			colorAspectMask: Int,
			colorBaseMipLevel: Int,
			colorLevelCount: Int,
			colorBaseArrayLayer: Int,
			colorLayerCount: Int,
			depthView: Long,
			depthImage: Long,
			depthFormat: Int,
			depthAspectMask: Int,
			depthBaseMipLevel: Int,
			depthLevelCount: Int,
			depthBaseArrayLayer: Int,
			depthLayerCount: Int,
			motionView: Long,
			motionImage: Long,
			motionFormat: Int,
			motionAspectMask: Int,
			motionBaseMipLevel: Int,
			motionLevelCount: Int,
			motionBaseArrayLayer: Int,
			motionLayerCount: Int,
			outputView: Long,
			outputImage: Long,
			outputFormat: Int,
			outputAspectMask: Int,
			outputBaseMipLevel: Int,
			outputLevelCount: Int,
			outputBaseArrayLayer: Int,
			outputLayerCount: Int,
			renderWidth: Int,
			renderHeight: Int,
			outputWidth: Int,
			outputHeight: Int,
			jitterX: Float,
			jitterY: Float,
			motionScaleX: Float,
			motionScaleY: Float,
			frameTimeMilliseconds: Float,
			resetHistory: Boolean,
		): Int = throw UnsupportedOperationException()
	}
}
