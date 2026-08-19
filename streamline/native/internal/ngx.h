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
 * Every preset SDK 310.7.0 documents as usable.
 *
 * The removed, deprecated, and reserved values are refused rather than forwarded: NGX answers
 * them by silently reverting to its own default, which is the one outcome an explicitly chosen
 * preset exists to prevent.
 */
bool valid_render_preset(uint32_t renderPreset) noexcept;

} // namespace mc_dlss

#endif
