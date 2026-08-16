#ifndef MC_DLSS_INTERNAL_NGX_H
#define MC_DLSS_INTERNAL_NGX_H

#include "internal/state.h"

/*
 * The NGX vocabulary the public ABI keeps: the quality-mode and render-preset values the
 * callers pass in and the bridge maps onto sl:: types in the Streamline unit.
 *
 * The direct-NGX implementation is retired. Nothing here talks to the NGX runtime: there is
 * no initialization, extension query, feature creation, evaluation, release, or shutdown, and
 * the only NGX symbols left are the enums and result codes the ABI contract names. The DLSS
 * 310.7.0 headers stay on the include path as reference vocabulary and nothing else.
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
