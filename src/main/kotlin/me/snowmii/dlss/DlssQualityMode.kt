package me.snowmii.dlss

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
enum class DlssQualityMode(
	val ngxValue: Int,
	val propertyValue: String,
	/**
	 * Preset this mode runs when nothing overrides it, from the SDK 310.7.0 header's own
	 * documented defaults. Written explicitly rather than left to the DLL, so the preset the
	 * acceptance record names is the preset that ran.
	 */
	val defaultPreset: DlssRenderPreset,
) {
	DLAA(5, "dlaa", DlssRenderPreset.K),
	QUALITY(2, "quality", DlssRenderPreset.K),
	BALANCED(1, "balanced", DlssRenderPreset.K),
	PERFORMANCE(0, "performance", DlssRenderPreset.M),
	ULTRA_PERFORMANCE(3, "ultra-performance", DlssRenderPreset.L),
}
