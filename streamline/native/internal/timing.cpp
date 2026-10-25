#include "internal/timing.h"

namespace mc_dlss {
namespace {

// Creates the query pool on first use, or gives up permanently on a device that cannot
// timestamp graphics work. Timing is diagnostic, so every failure here disables it rather
// than failing the frame that asked for it.
bool ensure_timing_pool() noexcept {
    if (!g_timing.supported) {
        return false;
    }
    if (g_timing.pool != VK_NULL_HANDLE) {
        return true;
    }
    if (g_state.device == VK_NULL_HANDLE || g_state.physicalDevice == VK_NULL_HANDLE) {
        return false;
    }

    VkPhysicalDeviceProperties properties{};
    vkGetPhysicalDeviceProperties(g_state.physicalDevice, &properties);
    if (properties.limits.timestampComputeAndGraphics == VK_FALSE ||
        properties.limits.timestampPeriod == 0.0f) {
        g_timing.supported = false;
        return false;
    }

    VkQueryPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
    poolInfo.queryType = VK_QUERY_TYPE_TIMESTAMP;
    poolInfo.queryCount = kTimingSlotCount * kTimingStampsPerSlot;
    VkQueryPool pool = VK_NULL_HANDLE;
    if (vkCreateQueryPool(g_state.device, &poolInfo, nullptr, &pool) != VK_SUCCESS) {
        g_timing.supported = false;
        return false;
    }

    g_timing.pool = pool;
    g_timing.timestampPeriod = properties.limits.timestampPeriod;
    return true;
}

// Reads one slot's stamps if the GPU is done with them, and never waits: an unfinished slot
// is left for the next pass around the ring rather than stalling the frame being recorded.
void collect_timing(const uint32_t slot) noexcept {
    const uint32_t count = g_timing.recordedStamps[slot];
    if (count == 0 || g_timing.pool == VK_NULL_HANDLE) {
        return;
    }

    uint64_t stamps[kTimingStampsPerSlot]{};
    const VkResult result = vkGetQueryPoolResults(
        g_state.device, g_timing.pool, slot * kTimingStampsPerSlot, count,
        sizeof(stamps), stamps, sizeof(uint64_t), VK_QUERY_RESULT_64_BIT);
    if (result != VK_SUCCESS) {
        return;
    }

    g_timing.recordedStamps[slot] = 0;
    if (count != kTimingStampsPerSlot || g_timing.sequences[slot] <= g_timing.lastResultSequence) return;
    g_timing.lastResultSequence = g_timing.sequences[slot];

    const float toMilliseconds = g_timing.timestampPeriod / 1000000.0f;
    g_timing.motionMs = static_cast<float>(stamps[1] - stamps[0]) * toMilliseconds;
    g_timing.evaluateMs = static_cast<float>(stamps[2] - stamps[1]) * toMilliseconds;
    g_timing.presentMs = static_cast<float>(stamps[3] - stamps[2]) * toMilliseconds;
    g_timing.totalMs = static_cast<float>(stamps[3] - stamps[0]) * toMilliseconds;
    g_timing.hasResult = true;
}

// Writes one stamp into the recording slot, advancing the written count and marking the slot
// pending when its last stamp lands. Every stage close stamps at BOTTOM_OF_PIPE: a stage's
// duration is measured between the point the slot opened and the point everything recorded
// before the stage has fully drained through the pipeline.
void write_timing_stamp(const VkCommandBuffer commandBuffer, const uint32_t index) noexcept {
    vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, g_timing.pool,
                        g_timing.recordingSlot * kTimingStampsPerSlot + index);
    g_timing.writtenStamps = index + 1;
    g_timing.recordedStamps[g_timing.recordingSlot] = g_timing.writtenStamps;
}

} // namespace

DlssFrameTiming g_timing;

void begin_frame_timing(const VkCommandBuffer commandBuffer) noexcept {
    if (!ensure_timing_pool()) {
        return;
    }

    g_timing.writtenStamps = 0;
    uint32_t slot = kTimingSlotCount;
    for (uint32_t step = 0; step < kTimingSlotCount; ++step) {
        const uint32_t candidate = (g_timing.nextSlot + step) % kTimingSlotCount;
        collect_timing(candidate);
        if (slot == kTimingSlotCount && g_timing.recordedStamps[candidate] == 0) slot = candidate;
    }
    if (slot == kTimingSlotCount) return; // Busy slots are retained, never overwritten or waited on.
    g_timing.recordingSlot = slot;
    g_timing.nextSlot = (slot + 1) % kTimingSlotCount;
    g_timing.sequences[slot] = g_timing.nextSequence++;
    // Minecraft enables hostQueryReset. Clear old availability before any new GPU recording.
    vkResetQueryPool(g_state.device, g_timing.pool, slot * kTimingStampsPerSlot, kTimingStampsPerSlot);
    vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, g_timing.pool,
                        slot * kTimingStampsPerSlot);
    g_timing.writtenStamps = 1;
    g_timing.recordedStamps[slot] = 1;
}

void mark_frame_timing(const VkCommandBuffer commandBuffer, const uint32_t index) noexcept {
    if (g_timing.pool == VK_NULL_HANDLE || g_timing.writtenStamps == 0 || g_timing.writtenStamps != index) {
        return;
    }

    write_timing_stamp(commandBuffer, index);
}

void destroy_timing() noexcept {
    if (g_timing.pool != VK_NULL_HANDLE && g_state.device != VK_NULL_HANDLE) {
        wait_device_idle();
        vkDestroyQueryPool(g_state.device, g_timing.pool, nullptr);
    }
    g_timing = DlssFrameTiming{};
}

} // namespace mc_dlss
