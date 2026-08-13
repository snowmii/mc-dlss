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

// Shuts the Streamline runtime down while the caller's Vulkan device is still alive, after the
// module's own resources have been released. Streamline's plugins keep their worker threads
// (CUDA/NGX) running until slShutdown, and those threads reach into the live device: leaving
// them running through device/process teardown is what crashes process exit in sl.common.dll
// or nvcuda64.dll. Called by the teardown ordering unit; reset_state then clears the bootstrap
// flag with the rest of the struct, so a later mc_dlss_bootstrap_streamline re-runs slInit.
void shutdown_streamline() noexcept;

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

// Records the DLSS-G per-frame 2x options with slDLSSGSetOptions: mode eOn, one generated
// frame per real one, retained resources while off, UI recomposition, the queue-parallelism
// mode, the declared back-buffer count, the render/output extents from the stored
// configuration, and the five required formats. Requires sl_session_ready; returns
// kNotInitialized without it and kInvalidParameter while the stored dimensions are still
// zero (no successful mc_dlss_configure yet).
int32_t record_fg_options(uint32_t numBackBuffers) noexcept;

// Tags one frame's DLSS SR resources on the caller's command buffer via slGetNewFrameToken +
// slSetTagForFrame. The engine's colour and depth are always tagged; the module's motion and
// output images are tagged as well once they have been acquired for the configured size. The
// frame token this call obtains is retained in state for the evaluation to consume, so a
// repeated tag for the same frame reuses the token rather than advancing the frame.
int32_t tag_sr_resources(const McDlssTagInfo& info) noexcept;

// Tags one frame's DLSS-G resources on the caller's command buffer via slGetNewFrameToken +
// slSetTagForFrame: the engine's render-sized depth (D32_SFLOAT), its output-sized HUD-less
// colour and UI colour+alpha (both R8G8B8A8_UNORM), and the module's own motion image as the
// motion-vector source. The formats must match the ones the FG options recorded. All four tag
// with eValidUntilPresent against the same frame token the SR tag obtains and retains for the
// frame, so a repeated tag reuses the token rather than advancing the frame and the frame's
// evaluation consumes it. Requires the FG options to have recorded successfully for the
// stored configuration and the module's images to exist at the configured size; returns
// kInvalidParameter before either.
int32_t tag_fg_resources(const McDlssFgTagInfo& info) noexcept;

// Records the frame's DLSS SR evaluation on the caller's command buffer: the per-frame
// constants (slSetConstants) and then the feature evaluation (slEvaluateFeature), both on the
// frame token mc_dlss_tag_sr_resources obtained and retained for this frame. Consuming the
// token clears it. The caller owns the layout transitions around this call and restores them
// whether or not it succeeds.
int32_t record_sr_evaluation(const McDlssEvaluateInfo& info,
                             VkCommandBuffer commandBuffer) noexcept;

} // namespace mc_dlss

#endif
