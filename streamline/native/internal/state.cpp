#include "internal/state.h"

namespace mc_dlss {

DlssState g_state;
std::mutex g_mutex;

void reset_state() noexcept {
    g_state = DlssState{};
}

void FrameEligibility::armSr(const uint32_t frameIndex) noexcept {
    srRecorded_ = true;
    srIndex_ = frameIndex;
}

void FrameEligibility::armFg(const uint32_t frameIndex) noexcept {
    fgRecorded_ = true;
    fgIndex_ = frameIndex;
}

bool FrameEligibility::srArmedAt(const uint32_t frameIndex) const noexcept {
    return srRecorded_ && srIndex_ == frameIndex;
}

bool FrameEligibility::tagIndexes(uint32_t* srFrameIndex,
                                  uint32_t* fgFrameIndex) const noexcept {
    if (!srRecorded_ || !fgRecorded_) {
        return false;
    }
    *srFrameIndex = srIndex_;
    *fgFrameIndex = fgIndex_;
    return true;
}

bool FrameEligibility::tagSetComplete() const noexcept {
    return srRecorded_ && fgRecorded_ && srIndex_ == fgIndex_;
}

void FrameEligibility::consumeForHandoff() noexcept {
    srRecorded_ = false;
    srIndex_ = 0;
    fgRecorded_ = false;
    fgIndex_ = 0;
    presentArmed_ = true;
}

bool FrameEligibility::presentStartPending() const noexcept {
    return !presentStartEmitted_ && token_ != nullptr;
}

void FrameEligibility::consumePresent() noexcept {
    presentStartEmitted_ = false;
    presentArmed_ = false;
    srRecorded_ = false;
    fgRecorded_ = false;
    token_ = nullptr;
}

bool FrameEligibility::copiesNeededFor(const uint32_t frameIndex) const noexcept {
    return !copiesRecorded_ || copiedIndex_ != frameIndex;
}

void FrameEligibility::recordCopies(const uint32_t frameIndex) noexcept {
    copiesRecorded_ = true;
    copiedIndex_ = frameIndex;
}

void FrameEligibility::invalidate() noexcept {
    // The retained token belongs to a frame whose records are being dropped with it: the next
    // tag must obtain a fresh token rather than advance the frame under a stale one. Both tag
    // records and their indexes clear together - a handoff reads the two sides as one set, so
    // one side can never outlive the other's invalidation - and the copy record clears with
    // the token it was recorded under, so the next frame's first FG tag rebuilds the copies
    // rather than skipping them as the stale frame's second call.
    *this = FrameEligibility{};
}

void MarkerLog::record(const uint32_t type, const sl::FrameToken* frameToken) noexcept {
    // The type indexes the count array, so a value outside the log's vocabulary would write
    // past it. Every caller passes one of its own enum values; the guard is here because
    // nothing else stands between an enum widened without its capacity and a stray write.
    if (type >= kMarkerTypeCapacity) {
        return;
    }
    events[eventCount % kMarkerLogSize] =
        MarkerEvent{type, static_cast<uint32_t>(*frameToken)};
    eventCount += 1;
    typeCounts[type] += 1;
}

int32_t MarkerLog::read(uint32_t* outTypeCounts, const uint32_t typeCount,
                        uint32_t* outEventCount, uint32_t* outEvents,
                        const uint32_t eventsCapacity) const noexcept {
    if (outTypeCounts == nullptr || outEventCount == nullptr || outEvents == nullptr) {
        return kInvalidParameter;
    }
    // The oracle answers only once this session actually emitted a marker: before that there
    // is no event any marker was emitted under, and the refusal is exactly what makes
    // "refused or pre-ready calls emit no markers" observable to the test - a call that leaked
    // markers would populate the log before the test expects it to.
    if (eventCount == 0) {
        return kNotInitialized;
    }
    for (uint32_t i = 0; i < typeCount && i < kMarkerTypeCapacity; ++i) {
        outTypeCounts[i] = typeCounts[i];
    }
    *outEventCount = eventCount;
    // The ring holds the most recent min(eventCount, kMarkerLogSize) events, oldest first:
    // the slot at eventCount % kMarkerLogSize holds the oldest kept event (it is the next to
    // be overwritten), and the kept events follow it around the ring.
    const uint32_t kept = eventCount < kMarkerLogSize ? eventCount : kMarkerLogSize;
    const uint32_t copied = eventsCapacity < kept ? eventsCapacity : kept;
    const uint32_t oldest = (eventCount - kept) % kMarkerLogSize;
    for (uint32_t i = 0; i < copied; ++i) {
        const MarkerEvent& event = events[(oldest + i) % kMarkerLogSize];
        outEvents[i * 2] = event.type;
        outEvents[i * 2 + 1] = event.frameIndex;
    }
    return kSuccess;
}

bool images_match_configuration() noexcept {
    return g_state.motionImage.view != VK_NULL_HANDLE &&
           g_state.outputImage.view != VK_NULL_HANDLE &&
           g_state.imagesRenderWidth == g_state.renderWidth &&
           g_state.imagesRenderHeight == g_state.renderHeight &&
           g_state.imagesOutputWidth == g_state.outputWidth &&
           g_state.imagesOutputHeight == g_state.outputHeight;
}

void wait_device_idle() noexcept {
    if (g_state.device != VK_NULL_HANDLE) {
        // Result deliberately ignored: on a device already lost there is nothing left to
        // wait for and nothing left to salvage, and the destroys still have to happen.
        vkDeviceWaitIdle(g_state.device);
    }
}

void record_layout_transition(const VkCommandBuffer commandBuffer, const VkImage image,
                              const VkImageSubresourceRange& subresourceRange,
                              const VkImageLayout oldLayout,
                              const VkImageLayout newLayout) noexcept {
    if (oldLayout == newLayout) {
        return;
    }
    VkImageMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.srcAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    barrier.oldLayout = oldLayout;
    barrier.newLayout = newLayout;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange = subresourceRange;
    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                         VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 1,
                         &barrier);
}

VkImageLayout current_layout_of(const uint64_t image) noexcept {
    if (g_state.motionImage.image != VK_NULL_HANDLE &&
        image == to_uint64(g_state.motionImage.image)) {
        return g_state.motionImage.layout;
    }
    if (g_state.outputImage.image != VK_NULL_HANDLE &&
        image == to_uint64(g_state.outputImage.image)) {
        return g_state.outputImage.layout;
    }
    return kEngineRestingLayout;
}

void note_layout_after_transition(const uint64_t image, const VkImageLayout layout) noexcept {
    if (g_state.motionImage.image != VK_NULL_HANDLE &&
        image == to_uint64(g_state.motionImage.image)) {
        g_state.motionImage.layout = layout;
    } else if (g_state.outputImage.image != VK_NULL_HANDLE &&
               image == to_uint64(g_state.outputImage.image)) {
        g_state.outputImage.layout = layout;
    }
}

} // namespace mc_dlss
