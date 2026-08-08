#ifndef MC_DLSS_INTERNAL_SL_DLSS_H
#define MC_DLSS_INTERNAL_SL_DLSS_H

#include "internal/state.h"

#include <cstdint>

/*
 * Everything that talks to the Streamline DLSS feature: the optimal-dimension query, the
 * per-viewport DLSS options, and the per-frame resource tags. The rest of the module deals in
 * Vulkan objects and NGX-valued ABI parameters; the sl:: types and result codes live here.
 */
namespace mc_dlss {

// Whether the Streamline session can answer DLSS calls: bootstrap has run and a Vulkan device
// has been recorded through mc_dlss_activate_vulkan_proxies (slSetVulkanInfo done).
bool sl_session_ready() noexcept;

// Answers from slDLSSGetOptimalSettings. Validates like the NGX query it replaces: non-zero
// output dimensions, a valid NGX-valued quality mode, and sane dimensions coming back. DLAA
// is anti-aliasing at native resolution, so it returns the output dimensions without asking.
int32_t query_optimal_dimensions_sl(uint32_t outputWidth, uint32_t outputHeight,
                                    uint32_t qualityMode, uint32_t* renderWidth,
                                    uint32_t* renderHeight) noexcept;

// Records the configuration currently stored with slDLSSSetOptions, mapping the NGX-valued
// quality mode onto sl::DLSSMode and the render preset onto the preset field sl::DLSSOptions
// carries for that mode. Requires sl_session_ready; returns kNotInitialized otherwise.
int32_t record_sr_options() noexcept;

// Tags one frame's DLSS SR resources on the caller's command buffer via slGetNewFrameToken +
// slSetTagForFrame. The engine's colour and depth are always tagged; the module's motion and
// output images are tagged as well once they have been acquired for the configured size.
int32_t tag_sr_resources(const McDlssTagInfo& info) noexcept;

} // namespace mc_dlss

#endif
