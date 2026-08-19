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
	/** Preset this session runs; [SRModelPreset.M] unless one was asked for. Independent of [qualityMode]. */
	val renderPreset: SRModelPreset = SRModelPreset.M,
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
 * NGX picks a preset for every mode whether or not one is asked for, and which one it picks moves
 * with the DLL and with driver-side overrides. A session that never names a preset therefore
 * cannot report which model produced its frames. The preset is always written, independently of
 * the quality mode, using [M] unless the user selects another.
 *
 * Only the presets SDK 310.7.0 documents as usable are here. A through D were removed from the
 * SDK, E and F are deprecated, and G through I and N and O revert to default behaviour, so none of
 * them is a choice this mod offers.
 */
enum class SRModelPreset(
	val sdkValue: Int,
	val propertyValue: String,
) {
	/** Transformer; NVIDIA's documented Quality/Balanced/DLAA model. Heavier temporal history. */
	K(11, "k"),

	/** Transformer; NVIDIA's documented Ultra Performance model. */
	L(12, "l"),

	/** Transformer; NVIDIA's documented Performance model, and this mod's fallback. */
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
 * NGX defines and does not implement. Nothing here is ordered by [sdkValue]: the enum order is the
 * order a reviewer cycles through, sharpest first.
 *
 * [DLAA] renders at the output size, so it is the only mode whose render and output dimensions are
 * equal - the degenerate case of every ratio the renderer computes.
 */
enum class SRMode(
	val sdkValue: Int,
	val propertyValue: String,
) {
	DLAA(5, "dlaa"),
	QUALITY(2, "quality"),
	BALANCED(1, "balanced"),
	PERFORMANCE(0, "performance"),
	ULTRA_PERFORMANCE(3, "ultra-performance"),
}
