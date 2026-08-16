package me.snowmii.dlss.session

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
	/** Preset this session runs; the mode's own documented default unless one was asked for. */
	val renderPreset: SRModelPreset = qualityMode.defaultPreset,
	val outputDimensions: Dimensions,
	val sdkPath: Path?,
	val nativeLibraryPath: Path?,
	val dataPath: Path?,
	val warnings: List<String>,
)

/**
 * The DLSS Super Resolution model a session runs, in the values
 * `NVSDK_NGX_DLSS_Hint_Render_Preset` gives them.
 *
 * NGX picks a preset for every mode whether or not one is asked for, and which one it picks moves
 * with the DLL and with driver-side overrides. A session that never names a preset therefore
 * cannot say afterwards which model produced its frames, which is exactly what the acceptance
 * record has to state. So the preset is always written, and the mode's documented default is what
 * it is written to unless the reviewer asks for another.
 *
 * Only the presets SDK 310.7.0 documents as usable are here. A through D were removed from the
 * SDK, E and F are deprecated, and G through I and N and O revert to default behaviour, so none of
 * them is a choice this mod offers.
 */
enum class SRModelPreset(
	val ngxValue: Int,
	val propertyValue: String,
) {
	/** Transformer, best image quality, default for DLAA/Quality/Balanced. */
	K(11, "k"),

	/** Transformer, less ghosting than [K] and more flicker. */
	J(10, "j"),

	/** Transformer, default for Ultra Performance. */
	L(12, "l"),

	/** Transformer, default for Performance. */
	M(13, "m"),
	;

	companion object {
		fun fromPropertyValue(value: String): SRModelPreset? =
			entries.firstOrNull { it.propertyValue == value }
	}
}

/**
 * The NGX performance/quality mode a session runs, in the values `NVSDK_NGX_PerfQuality_Value`
 * gives them.
 *
 * The three original modes keep their NGX values; [ULTRA_PERFORMANCE] and [DLAA] are the extended
 * ones, and their gap in the numbering is `NVSDK_NGX_PerfQuality_Value_UltraQuality` (4), which
 * NGX defines and does not implement. Nothing here is ordered by [ngxValue]: the enum order is the
 * order a reviewer cycles through, sharpest first.
 *
 * [DLAA] renders at the output size, so it is the only mode whose render and output dimensions are
 * equal - the degenerate case of every ratio the renderer computes.
 */
enum class SRMode(
	val ngxValue: Int,
	val propertyValue: String,
	val defaultPreset: SRModelPreset,
) {
	DLAA(5, "dlaa", SRModelPreset.K),
	QUALITY(2, "quality", SRModelPreset.K),
	BALANCED(1, "balanced", SRModelPreset.K),
	PERFORMANCE(0, "performance", SRModelPreset.M),
	ULTRA_PERFORMANCE(3, "ultra-performance", SRModelPreset.L),
}
