#ifndef MC_DLSS_INTERNAL_MOTION_H
#define MC_DLSS_INTERNAL_MOTION_H

#include "internal/state.h"

#include <cstdint>

namespace mc_dlss {

constexpr uint32_t kMotionWorkgroupSize = 8;

inline uint32_t motion_workgroup_count(uint32_t extent) noexcept {
    return (extent + kMotionWorkgroupSize - 1) / kMotionWorkgroupSize;
}

int32_t create_motion_pass() noexcept;

void destroy_motion_pass() noexcept;
void invalidate_motion_bindings() noexcept;

int32_t record_motion(const McDlssMotionInfo& info) noexcept;

int32_t query_motion_probe(float* motionX, float* motionY, float* depth, int32_t* slot) noexcept;

}

#endif
