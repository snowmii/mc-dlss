package me.snowmii.dlss.session
import me.snowmii.dlss.nativeSource
import me.snowmii.dlss.bridge.EvaluationRequest
import me.snowmii.dlss.bridge.PresentTarget
import me.snowmii.dlss.bridge.MotionRequest
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.config.ModConfig
import me.snowmii.dlss.bridge.NativeApi
import me.snowmii.dlss.bridge.DlssEvaluationImages
import me.snowmii.dlss.bridge.DlssFrameTimings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
	private val nativeSource = nativeSource("internal/ngx.cpp")

	@Test
	fun everyImplementedModeIsSelectableByProperty() {
		val selected = listOf(
			"quality" to SRMode.QUALITY,
			"balanced" to SRMode.BALANCED,
			"performance" to SRMode.PERFORMANCE,
			"ultra-performance" to SRMode.ULTRA_PERFORMANCE,
			"ultra-perf" to SRMode.ULTRA_PERFORMANCE,
			"dlaa" to SRMode.DLAA,
		)

		selected.forEach { (value, expected) ->
			val config = configFrom(ModConfig.MODE_PROPERTY to value)
			assertEquals(expected, config.qualityMode, "$value must select $expected")
			assertTrue(config.warnings.isEmpty(), "$value must not warn: ${config.warnings}")
		}
	}

	@Test
	fun modeCarriesTheNgxValueTheSdkDefinesForIt() {
		// NVSDK_NGX_PerfQuality_Value: MaxPerf 0, Balanced 1, MaxQuality 2, UltraPerformance 3,
		// UltraQuality 4 (defined, never implemented), DLAA 5.
		assertEquals(0, SRMode.PERFORMANCE.ngxValue)
		assertEquals(1, SRMode.BALANCED.ngxValue)
		assertEquals(2, SRMode.QUALITY.ngxValue)
		assertEquals(3, SRMode.ULTRA_PERFORMANCE.ngxValue)
		assertEquals(5, SRMode.DLAA.ngxValue)
		assertTrue(SRMode.entries.none { it.ngxValue == 4 }, "UltraQuality is not offered")
	}

	@Test
	fun everyModeDefaultsToTheDocumentedPresetForIt() {
		assertEquals(SRModelPreset.K, SRMode.DLAA.defaultPreset)
		assertEquals(SRModelPreset.K, SRMode.QUALITY.defaultPreset)
		assertEquals(SRModelPreset.K, SRMode.BALANCED.defaultPreset)
		assertEquals(SRModelPreset.M, SRMode.PERFORMANCE.defaultPreset)
		assertEquals(SRModelPreset.L, SRMode.ULTRA_PERFORMANCE.defaultPreset)

		SRMode.entries.forEach { mode ->
			val config = configFrom(ModConfig.MODE_PROPERTY to mode.propertyValue)
			assertEquals(mode.defaultPreset, config.renderPreset, "${mode.propertyValue} default preset")
		}
	}

	@Test
	fun presetPropertyOverridesTheModeDefaultAndDegradesToItWhenUnreadable() {
		val overridden = configFrom(
			ModConfig.MODE_PROPERTY to "performance",
			ModConfig.PRESET_PROPERTY to "j",
		)
		assertEquals(SRModelPreset.J, overridden.renderPreset)
		assertTrue(overridden.warnings.isEmpty())

		val explicitDefault = configFrom(
			ModConfig.MODE_PROPERTY to "performance",
			ModConfig.PRESET_PROPERTY to "default",
		)
		assertEquals(SRModelPreset.M, explicitDefault.renderPreset)

		// Preset E is deprecated and A through D were removed from the SDK, so naming one is a
		// value the mod refuses rather than forwards.
		val invalid = configFrom(
			ModConfig.MODE_PROPERTY to "ultra-performance",
			ModConfig.PRESET_PROPERTY to "e",
		)
		assertEquals(SRModelPreset.L, invalid.renderPreset, "degrades to the mode's own default")
		assertTrue(
			invalid.warnings.any { it.contains(ModConfig.PRESET_PROPERTY) && it.contains("e") },
			"invalid preset must warn: ${invalid.warnings}",
		)
	}

	@Test
	fun configuredModeAndPresetBothReachTheNativeBridge() {
		val session = DlssSession(
			DlssStartupConfig(
				enabled = true,
				qualityMode = SRMode.ULTRA_PERFORMANCE,
				renderPreset = SRModelPreset.J,
				outputDimensions = output,
				sdkPath = Path.of("sdk"),
				nativeLibraryPath = null,
				dataPath = Path.of("data"),
				warnings = emptyList(),
			),
		)
		val native = RecordingNative()

		assertNotNull(LifecycleAdapter(session, native).initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")))
		assertEquals(SRMode.ULTRA_PERFORMANCE.ngxValue, native.queriedQualityMode)
		assertEquals(SRMode.ULTRA_PERFORMANCE.ngxValue, native.configuredQualityMode)
		assertEquals(SRModelPreset.J.ngxValue, native.configuredRenderPreset)
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
			.substringAfter("int32_t ensure_feature(")
			.substringBefore("int32_t record_evaluation(")
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
		return ModConfig.from(properties).startupConfig
	}

	/** Records what the adapter passed down, and nothing else; every later stage is unreachable. */
	private class RecordingNative : NativeApi {
		var queriedQualityMode: Int? = null
		var configuredQualityMode: Int? = null
		var configuredRenderPreset: Int? = null

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
			return NativeApi.SUCCESS_RESULT
		}

		override fun acquireImages(): DlssEvaluationImages = throw UnsupportedOperationException()

		override fun releaseImages(): Int = throw UnsupportedOperationException()

		override fun waitDeviceIdle(): Int = NativeApi.SUCCESS_RESULT

		override fun frameTimings(): DlssFrameTimings? = null

		override fun writeMotion(request: MotionRequest): Int = throw UnsupportedOperationException()

		override fun presentOutput(target: PresentTarget): Int = throw UnsupportedOperationException()

		override fun evaluate(request: EvaluationRequest): Int = throw UnsupportedOperationException()
	}
}
