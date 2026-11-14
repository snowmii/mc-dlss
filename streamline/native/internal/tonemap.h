#ifndef MC_DLSS_INTERNAL_TONEMAP_H
#define MC_DLSS_INTERNAL_TONEMAP_H

#include "internal/state.h"

#include <cstdint>

namespace mc_dlss {

int32_t record_tone_map(VkCommandBuffer commandBuffer, float exposure,
                       const McDlssImage& reflection, const McDlssImage& output) noexcept;

void destroy_tone_map_pass() noexcept;

void invalidate_tone_map_bindings() noexcept;

}

#endif
