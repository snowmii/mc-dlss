#ifndef MC_DLSS_INTERNAL_NGX_H
#define MC_DLSS_INTERNAL_NGX_H

#include "internal/state.h"

/*
 * NGX quality-mode and render-preset values the ABI speaks in. This unit does not talk to
 * the NGX runtime; Streamline maps these onto sl:: types. DLSS 310.7.0 headers are
 * reference vocabulary only.
 */
namespace mc_dlss {

bool valid_quality_mode(uint32_t qualityMode) noexcept;

/**
 * Every preset SDK 310.7.0 documents as usable for Super Resolution.
 *
 * [NVSDK_NGX_DLSS_Hint_Render_Preset_Default] lets NVIDIA App / OTA choose the model. Named
 * presets lock the model. Removed, deprecated, and reserved values are refused rather than
 * forwarded: NGX answers them by silently reverting to its own default.
 */
bool valid_render_preset(uint32_t renderPreset) noexcept;

/** RR model presets: 0 = NVIDIA App / OTA, else D/E/F (`sl::DLSSDPreset` 4/5/6). */
bool valid_rr_preset(uint32_t rrPreset) noexcept;

} // namespace mc_dlss

#endif
