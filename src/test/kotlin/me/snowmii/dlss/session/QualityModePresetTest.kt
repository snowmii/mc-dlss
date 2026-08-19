package me.snowmii.dlss.session
import me.snowmii.dlss.DlssSession
import me.snowmii.dlss.DlssStartupConfig
import me.snowmii.dlss.SRMode
import me.snowmii.dlss.SRModelPreset
import me.snowmii.dlss.streamline.LifecycleAdapter
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

	@Test
	fun `every implemented mode is selectable by property`() {
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
	fun `mode carries the NGX value the SDK defines for it`() {
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
	fun `unset or unreadable preset falls back to M for every mode`() {
		SRMode.entries.forEach { mode ->
			val config = configFrom(ModConfig.MODE_PROPERTY to mode.propertyValue)
			assertEquals(SRModelPreset.M, config.renderPreset, "${mode.propertyValue} fallback preset")
		}
	}

	@Test
	fun `preset property overrides the fallback and degrades to M when unreadable`() {
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
	fun `configured mode and preset both reach the native bridge`() {
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
	fun `DLAA renders at output resolution without asking the optimal-settings query`() {
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
