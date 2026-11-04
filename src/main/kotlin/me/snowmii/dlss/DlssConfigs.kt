package me.snowmii.dlss

import me.snowmii.streamline.Dimensions
import java.nio.file.Path

/**
 * The session's DLSS contract configuration: what the running DLSS feature is allowed to do.
 *
 * A value, not a reader: the one place that names and parses the `-Dmc.dlss.*` knobs is
 * `ModConfig`, and the session's config is that handle's `ModConfig.startupConfig` view. Tests
 * build this data class directly; production builds it once at the mod entrypoint.
 */
data class DlssStartupConfig(
	val enabled: Boolean,
	val qualityMode: SRMode,
	/** Preset this session runs; [SRModelPreset.DEFAULT] follows NVIDIA App / OTA unless locked. */
	val renderPreset: SRModelPreset = SRModelPreset.DEFAULT,
	/**
	 * Output size the session starts against. It is the size in effect only until the first world
	 * frame reports the client's real main target; see [DlssSession.outputDimensions]. A session
	 * with [outputPinned] set keeps this size for its whole life.
	 */
	val outputDimensions: Dimensions,
	val sdkPath: Path?,
	val nativeLibraryPath: Path?,
	val dataPath: Path?,
	val warnings: List<String>,
	/**
	 * Whether `mc.dlss.output-width` / `mc.dlss.output-height` were named explicitly, which pins
	 * the session to [outputDimensions] and refuses every frame at another size. Unset - the
	 * default - lets the output size follow the client's main render target.
	 */
	val outputPinned: Boolean = false,
)

/**
 * The DLSS Super Resolution model a session runs, in the values
 * `NVSDK_NGX_DLSS_Hint_Render_Preset` gives them.
 *
 * [DEFAULT] leaves the model to NGX / NVIDIA App / OTA (`eDefault`). Named presets lock the
 * model so App overrides cannot change it.
 *
 * Only the presets SDK 310.9.1 documents as usable are offered besides [DEFAULT]. A through D
 * were removed from the SR SDK, E and F are deprecated, and G through I and N and O revert to
 * default behaviour, so none of them is a choice this mod offers for SR.
 */
enum class SRModelPreset(
	val sdkValue: Int,
	val propertyValue: String,
	val displayName: String,
) {
	/** NVIDIA App / OTA may select the model (`NVSDK_NGX_DLSS_Hint_Render_Preset_Default`). */
	DEFAULT(0, "default", "Default"),

	/** Transformer; NVIDIA's documented Quality/Balanced/DLAA model. Heavier temporal history. */
	K(11, "k", "Preset K"),

	/** Transformer; NVIDIA's documented Ultra Performance model. */
	L(12, "l", "Preset L"),

	/** Transformer; NVIDIA's documented Performance model. */
	M(13, "m", "Preset M"),
	;

	companion object {
		fun fromPropertyValue(value: String): SRModelPreset? =
			entries.firstOrNull { it.propertyValue == value }
	}
}

/**
 * DLSS Ray Reconstruction model presets (`sl::DLSSDPreset`).
 *
 * [DEFAULT] leaves the model to NGX / NVIDIA App / OTA (`eDefault`). Named D/E/F lock the
 * model. Streamline 2.14.1 headers designate F as the latest and default transformer model;
 * keep all three for comparison.
 */
enum class RRModelPreset(
	val sdkValue: Int,
	val propertyValue: String,
	val displayName: String,
) {
	/** NVIDIA App / OTA may select the RR model (`sl::DLSSDPreset::eDefault`). */
	DEFAULT(0, "default", "Default"),

	/** Default transformer model in public Streamline RR headers. */
	D(4, "d", "Preset D"),

	/** Prior “latest” transformer; required when a DoF guide is needed per public headers. */
	E(5, "e", "Preset E"),

	/** DLSS 4.5 RR model selection target. */
	F(6, "f", "Preset F"),
	;

	companion object {
		fun fromPropertyValue(value: String): RRModelPreset? =
			entries.firstOrNull { it.propertyValue == value }
	}
}

/**
 * The NGX performance/quality mode a session runs, in the values `NVSDK_NGX_PerfQuality_Value`
 * gives them.
 *
 * The three original modes keep their NGX values; [ULTRA_PERFORMANCE] and [DLAA] are the extended
 * ones, and their gap in the numbering is `NVSDK_NGX_PerfQuality_Value_UltraQuality` (4), which
 * NGX defines and does not implement. Nothing here is ordered by [sdkValue]: the enum order is the
 * order a reviewer cycles through, sharpest first.
 *
 * [DLAA] renders at the output size, so it is the only mode whose render and output dimensions are
 * equal - the degenerate case of every ratio the renderer computes.
 */
enum class SRMode(
	val sdkValue: Int,
	val propertyValue: String,
	val displayName: String,
) {
	DLAA(5, "dlaa", "DLAA"),
	QUALITY(2, "quality", "Quality"),
	BALANCED(1, "balanced", "Balanced"),
	PERFORMANCE(0, "performance", "Performance"),
	ULTRA_PERFORMANCE(3, "ultra-performance", "Ultra Performance"),
}

/**
 * NVIDIA Reflex low-latency modes (`sl::ReflexMode`).
 */
enum class ReflexMode(
	val sdkValue: Int,
	val propertyValue: String,
	val displayName: String,
) {
	OFF(0, "off", "Off"),
	ON(1, "on", "On"),
	BOOST(2, "boost", "On + Boost");

	companion object {
		fun fromPropertyValue(value: String): ReflexMode? =
			entries.firstOrNull { it.propertyValue.equals(value, ignoreCase = true) }
	}
}
