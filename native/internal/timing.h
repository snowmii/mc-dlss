#ifndef MC_DLSS_INTERNAL_TIMING_H
#define MC_DLSS_INTERNAL_TIMING_H

#include "internal/state.h"

/*
 * GPU-side timing of the three stages this module records.
 *
 * Frame rate cannot answer where the time goes: a client whose frame length is set by the CPU
 * shows the same rate whether the DLSS chain costs 0.2ms or 2ms, and the GPU utilization that
 * does move is a ratio against a wall clock the renderer chose. Timestamps are the only thing
 * that separates NGX's own cost from the copy and the barriers around it.
 *
 * Four stamps per frame - one before the motion pass, one after it, one after the evaluation,
 * one after the copy - into a ring of slots, so results are read for a frame the GPU finished
 * several frames ago and no read ever waits. A frame that skips a stage leaves its slot
 * incomplete and is dropped rather than reported as a fast one.
 */
namespace mc_dlss {

constexpr uint32_t kTimingSlotCount = 4;
constexpr uint32_t kTimingStampsPerSlot = 4;

struct DlssFrameTiming {
    VkQueryPool pool = VK_NULL_HANDLE;
    bool supported = true;
    float timestampPeriod = 0.0f;
    uint32_t recordingSlot = 0;
    uint32_t nextSlot = 0;
    /** Stamps written into the recording slot so far, which is also the next stamp's index. */
    uint32_t writtenStamps = 0;
    bool pending[kTimingSlotCount] = {};
    bool hasResult = false;
    float motionMs = 0.0f;
    float evaluateMs = 0.0f;
    float presentMs = 0.0f;
    float totalMs = 0.0f;
};

extern DlssFrameTiming g_timing;

// Opens a slot for the frame about to be recorded, collecting whatever the same slot's frame
// left behind on its way past.
void begin_frame_timing(VkCommandBuffer commandBuffer) noexcept;

// Closes one stage. [index] is checked against what the slot already holds, so a frame whose
// stages did not all run - a failed evaluation, a frame with no destination to copy into -
// abandons its slot instead of reporting a gap as a duration.
void mark_frame_timing(VkCommandBuffer commandBuffer, uint32_t index) noexcept;

void destroy_timing() noexcept;

} // namespace mc_dlss

#endif
