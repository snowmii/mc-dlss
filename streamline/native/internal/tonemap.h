#ifndef MC_DLSS_INTERNAL_TONEMAP_H
#define MC_DLSS_INTERNAL_TONEMAP_H

#include "internal/state.h"

#include <cstdint>

namespace mc_dlss {

// Records the unchanged HDR conversion into an RGBA8 storage target. All input and output
// layouts are restored, with visibility for the destination's next engine consumer.
int32_t record_tone_map(VkCommandBuffer commandBuffer, float exposure,
                       const McDlssImage& reflection, const McDlssImage& output) noexcept;

// Destroys the cached tone-map pipeline and descriptor ring.
void destroy_tone_map_pass() noexcept;

// Forgets descriptors that refer to images released during reconfiguration.
void invalidate_tone_map_bindings() noexcept;

} // namespace mc_dlss

#endif
