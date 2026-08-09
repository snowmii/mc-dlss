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
 * several frames ago and no read ever waits. A frame whose stages did not all run leaves its
 * slot incomplete and is dropped rather than reported as a fast one; the one intentional
 * exception is the velocity route, whose motion stage is skipped and whose slot records the
 * skip explicitly, so the frame still reports with a motion cost of exactly zero.
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
    /**
     * Per-slot record of a skipped motion stage. A skipped stage never gets a duration from
     * stamp deltas: the stamp-0-to-stamp-1 span of a slot that records no motion work would
     * only measure whatever earlier command buffers were still draining between the two
     * stamps, so the slot's motion cost is pinned to zero at collection instead. Set when the
     * slot is marked skipped, cleared when the slot is opened and when it is collected.
     */
    bool motionSkipped[kTimingSlotCount] = {};
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

// Marks the motion stage as skipped rather than closed after real work, the velocity route's
// timing open. The slot still completes through evaluate and present, but collection reports
// the motion stage as exactly zero instead of the stamp span: the skipped stage records no
// work, so a span would only measure whatever earlier command buffers were still draining
// between the open stamp and this one. The per-slot record pins the zero instead of trusting
// the span to be empty.
void mark_skipped_motion_timing(VkCommandBuffer commandBuffer) noexcept;

void destroy_timing() noexcept;

} // namespace mc_dlss

#endif
