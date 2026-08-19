package me.snowmii.dlss.session
import me.snowmii.dlss.readNativeSource
import me.snowmii.streamline.StreamlineSessionTestDouble
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.streamline.PresentTarget
import me.snowmii.streamline.MotionRequest
import me.snowmii.streamline.Dimensions
import me.snowmii.dlss.client.ModConfig
import me.snowmii.streamline.StreamlineSession
import me.snowmii.streamline.EvaluationImages
import me.snowmii.streamline.FrameTimings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.Properties

/**
 * Ultra Performance and DLAA are selectable, and every session runs a preset it named. NGX
 * selects a preset for every mode, so native configuration and readout record the chosen
 * preset rather than a DLL default.
 */
class QualityModePresetTest {
	private val output = Dimensions(2560, 1440)

	// Newlines are normalized because these assertions match the source text literally, and a
	// Windows checkout hands the same file back with CRLF.
	private val nativeSource = readNativeSource("internal/ngx.cpp")

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
		assertEquals(0, SRMode.PERFORMANCE.sdkValue)
		assertEquals(1, SRMode.BALANCED.sdkValue)
		assertEquals(2, SRMode.QUALITY.sdkValue)
		assertEquals(3, SRMode.ULTRA_PERFORMANCE.sdkValue)
		assertEquals(5, SRMode.DLAA.sdkValue)
		assertTrue(SRMode.entries.none { it.sdkValue == 4 }, "UltraQuality is not offered")
	}

	@Test
	fun unsetOrUnreadablePresetFallsBackToMForEveryMode() {
		SRMode.entries.forEach { mode ->
			val config = configFrom(ModConfig.MODE_PROPERTY to mode.propertyValue)
			assertEquals(SRModelPreset.M, config.renderPreset, "${mode.propertyValue} fallback preset")
		}
	}

	@Test
	fun presetPropertyOverridesTheFallbackAndDegradesToMWhenUnreadable() {
		val overridden = configFrom(
			ModConfig.MODE_PROPERTY to "performance",
			ModConfig.PRESET_PROPERTY to "k",
		)
		assertEquals(SRModelPreset.K, overridden.renderPreset)
		assertTrue(overridden.warnings.isEmpty())

		val explicitDefault = configFrom(
			ModConfig.MODE_PROPERTY to "quality",
			ModConfig.PRESET_PROPERTY to "default",
		)
		assertEquals(SRModelPreset.M, explicitDefault.renderPreset)

		// Preset E is deprecated and A through D are not in the SDK, so naming one is refused
		// rather than forwarded.
		val invalid = configFrom(
			ModConfig.MODE_PROPERTY to "ultra-performance",
			ModConfig.PRESET_PROPERTY to "e",
		)
		assertEquals(SRModelPreset.M, invalid.renderPreset, "degrades to M")
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
				renderPreset = SRModelPreset.K,
				outputDimensions = output,
				sdkPath = Path.of("sdk"),
				nativeLibraryPath = null,
				dataPath = Path.of("data"),
				warnings = emptyList(),
			),
		)
		val native = RecordingNativeApi()

		assertNotNull(LifecycleAdapter(session, native).initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")))
		assertEquals(SRMode.ULTRA_PERFORMANCE.sdkValue, native.queriedQualityMode)
		assertEquals(SRMode.ULTRA_PERFORMANCE.sdkValue, native.configuredQualityMode)
		assertEquals(SRModelPreset.K.sdkValue, native.configuredRenderPreset)
	}

	@Test
	fun nativeAcceptsEveryImplementedModeAndRefusesUltraQuality() {
		val validator = nativeSource
			.substringAfter("bool valid_quality_mode(")
			.substringBefore("bool valid_render_preset(")

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
	fun changingOnlyThePresetIsRecordedOnTheNextConfigure() {
		// Every configure records options; the plugin recreates when mode, size, or preset
		// change, so a preset-only change reaches the plugin on the next configure.
		val api = readNativeSource("mc_dlss_api.cpp")
		val configure = api.substringAfter("mc_dlss_configure").substringBefore("mc_dlss_acquire_images")
		assertTrue(
			configure.contains("record_sr_options()"),
			"configure must record the SL options on every configure",
		)
		assertTrue(
			!configure.contains("bootstrapComplete") && !configure.contains("capabilityParameters"),
			"no direct-NGX branch may remain inside configure",
		)
	}

	@Test
	fun dlaaRendersAtOutputResolutionWithoutAskingTheOptimalSettingsQuery() {
		// DLAA is 1:1, so slDLSSGetOptimalSettings is not asked; the bridge answers the output
		// dimensions directly.
		val query = readNativeSource("internal/sl_dlss.cpp")
			.substringAfter("int32_t query_optimal_dimensions_sl(")
			.substringBefore("int32_t record_sr_options(")
		assertTrue(
			query.contains("NVSDK_NGX_PerfQuality_Value_DLAA"),
			"DLAA is 1:1 by definition, so it must not depend on the optimal-settings query",
		)
		assertTrue(
			query.contains("*renderWidth = outputWidth") && query.contains("*renderHeight = outputHeight"),
			"DLAA's render dimensions are its output dimensions",
		)
	}

	@Test
	fun theRunningModesPresetIsMappedBeforeTheOptionsAreRecorded() {
		// The preset lands on sl::DLSSOptions for the running mode and slDLSSSetOptions records
		// the options after the mapping; the plugin recreates when the preset changes.
		val slOptions = readNativeSource("internal/sl_dlss.cpp")
		val record = slOptions
			.substringAfter("int32_t record_sr_options(")
			.substringBefore("int32_t record_sr_evaluation(")
		listOf(
			"options.performancePreset = preset",
			"options.balancedPreset = preset",
			"options.qualityPreset = preset",
			"options.ultraPerformancePreset = preset",
			"options.dlaaPreset = preset",
		).forEach { field ->
			assertTrue(record.contains(field), "every mode needs its own preset field: $field")
		}

		val presetWrite = record.indexOf("options.qualityPreset = preset")
		val setOptions = record.indexOf("slDLSSSetOptions")
		assertTrue(presetWrite >= 0 && setOptions >= 0, "the preset mapping and the record call must exist")
		assertTrue(presetWrite < setOptions, "the preset must be mapped before the options are recorded")
	}

	private fun configFrom(vararg entries: Pair<String, String>): DlssStartupConfig {
		val properties = Properties()
		entries.forEach { (name, value) -> properties.setProperty(name, value) }
		return ModConfig.from(properties).startupConfig
	}

	/** Records what the adapter passed down, and nothing else; every later stage is unreachable. */
	private class RecordingNativeApi : StreamlineSessionTestDouble() {
		var queriedQualityMode: Int? = null
		var configuredQualityMode: Int? = null
		var configuredRenderPreset: Int? = null

		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		): Int = StreamlineSession.SUCCESS_RESULT

		override fun queryOptimalDimensions(
			outputWidth: Int,
			outputHeight: Int,
			qualityMode: Int,
		): Dimensions {
			queriedQualityMode = qualityMode
			return Dimensions(853, 480)
		}

		override fun configureSuperResolution(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		): Int {
			configuredQualityMode = qualityMode
			configuredRenderPreset = renderPreset
			return StreamlineSession.SUCCESS_RESULT
		}

		override fun acquireImages(): EvaluationImages = throw UnsupportedOperationException()

		override fun releaseImages(): Int = throw UnsupportedOperationException()

		override fun waitDeviceIdle(): Int = StreamlineSession.SUCCESS_RESULT

		override fun frameTimings(): FrameTimings? = null

		override fun writeMotion(request: MotionRequest): Int = throw UnsupportedOperationException()

		override fun presentOutput(target: PresentTarget): Int = throw UnsupportedOperationException()

		override fun evaluateSuperResolution(request: EvaluationRequest): Int = throw UnsupportedOperationException()
	}
}
