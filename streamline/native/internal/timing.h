#ifndef MC_DLSS_INTERNAL_TIMING_H
#define MC_DLSS_INTERNAL_TIMING_H

#include "internal/state.h"

namespace mc_dlss {

constexpr uint32_t kTimingSlotCount = 4;
constexpr uint32_t kTimingStampsPerSlot = 4;

struct DlssFrameTiming {
    VkQueryPool pool = VK_NULL_HANDLE;
    bool supported = true;
    float timestampPeriod = 0.0f;
    uint32_t recordingSlot = 0;
    uint32_t nextSlot = 0;
    uint32_t writtenStamps = 0;
    uint32_t recordedStamps[kTimingSlotCount] = {};
    uint64_t sequences[kTimingSlotCount] = {};
    uint64_t nextSequence = 1;
    uint64_t lastResultSequence = 0;
    bool hasResult = false;
    float motionMs = 0.0f;
    float evaluateMs = 0.0f;
    float presentMs = 0.0f;
    float totalMs = 0.0f;
};

extern DlssFrameTiming g_timing;

void begin_frame_timing(VkCommandBuffer commandBuffer) noexcept;

void mark_frame_timing(VkCommandBuffer commandBuffer, uint32_t index) noexcept;

void destroy_timing() noexcept;

}

#endif
