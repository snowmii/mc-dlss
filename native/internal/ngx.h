#ifndef MC_DLSS_INTERNAL_NGX_H
#define MC_DLSS_INTERNAL_NGX_H

#include "internal/state.h"

#include <nvsdk_ngx_vk.h>

/*
 * Everything that talks to NGX itself: what it will accept, what it reports, and the
 * lifetimes of the feature and the capability parameters.
 *
 * The rest of the module deals in Vulkan objects and never in NGX ones, which is what keeps
 * the SDK's headers and result codes out of the units above and below this one.
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

/**
 * The capability parameter naming the preset for one quality mode.
 *
 * NGX keys the hint by mode rather than taking one preset, so the value only reaches the model
 * that is about to run if it is written under the mode's own name.
 */
const char* preset_parameter_for(uint32_t qualityMode) noexcept;

int32_t query_optimal_dimensions(uint32_t outputWidth, uint32_t outputHeight,
                                 uint32_t qualityMode, uint32_t* renderWidth,
                                 uint32_t* renderHeight) noexcept;

NVSDK_NGX_FeatureDiscoveryInfo make_discovery_info() noexcept;

/* Copy the i-th extension name; returns success with *extensionCount set on both
 * the count probe (name == nullptr) and a real copy. */
int32_t copy_extension_name(uint32_t index, char* name, uint32_t nameCapacity,
                            uint32_t* extensionCount, uint32_t count,
                            const VkExtensionProperties* properties) noexcept;

int32_t release_feature() noexcept;

int32_t destroy_capability_parameters() noexcept;

} // namespace mc_dlss

#endif
