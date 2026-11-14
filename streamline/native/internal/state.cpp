#include "internal/state.h"

#include <vector>

namespace mc_dlss {

DlssState g_state;
std::mutex g_mutex;

void reset_state() noexcept {
    g_state = DlssState{};
}

void store_rr_guides(const McDlssRrPrepareInfo& info) noexcept {
    g_state.rrArmed = info.enabled != 0;
    g_state.rrDiffuseAlbedo = g_state.rrArmed ? info.diffuse_albedo : McDlssImage{};
    g_state.rrSpecularAlbedo = g_state.rrArmed ? info.specular_albedo : McDlssImage{};
    g_state.rrNormalRoughness = g_state.rrArmed ? info.normal_roughness : McDlssImage{};
    g_state.rrSpecularHitDistance = g_state.rrArmed ? info.specular_hit_distance : McDlssImage{};
    g_state.rrSpecularMotion = g_state.rrArmed ? info.specular_motion : McDlssImage{};
    g_state.rrBiasCurrentColor = g_state.rrArmed ? info.bias_current_color : McDlssImage{};
    if (g_state.rrArmed) {
        g_state.rrPreset = info.rr_preset;
        for (int i = 0; i < 16; ++i) {
            g_state.rrWorldToCameraView[i] = info.world_to_camera_view[i];
            g_state.rrCameraViewToWorld[i] = info.camera_view_to_world[i];
        }
    }
}

sl::ResourceTag make_rr_bias_current_color_tag(sl::Resource& resource) noexcept {
    resource = sl::Resource{};
    const McDlssImage& image = g_state.rrBiasCurrentColor;
    if (g_state.rrArmed && valid_image(image)) {
        resource = sl::Resource(sl::ResourceType::eTex2d, from_uint64<void*>(image.image),
                                nullptr, from_uint64<void*>(image.view), kDlssInputLayout);
        resource.width = g_state.renderWidth;
        resource.height = g_state.renderHeight;
        resource.nativeFormat = image.format;
        resource.mipLevels = 1;
        resource.arrayLayers = 1;
        resource.usage = 0;
        static sl::SubresourceRange range{};
        range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        range.levelCount = 1;
        range.layerCount = 1;
        resource.next = &range;
    }
    resource.flags = 0;
    const sl::Extent extent{0, 0, g_state.renderWidth, g_state.renderHeight};
    return sl::ResourceTag(&resource, sl::kBufferTypeBiasCurrentColorHint,
                           sl::ResourceLifecycle::eValidUntilPresent, &extent);
}

sl::ResourceTag make_rr_disocclusion_mask_tag(sl::Resource& resource) noexcept {
    resource = sl::Resource{};
    const McDlssImage& image = g_state.rrBiasCurrentColor;
    if (g_state.rrArmed && valid_image(image)) {
        resource = sl::Resource(sl::ResourceType::eTex2d, from_uint64<void*>(image.image),
                                nullptr, from_uint64<void*>(image.view), kDlssInputLayout);
        resource.width = g_state.renderWidth;
        resource.height = g_state.renderHeight;
        resource.nativeFormat = image.format;
        resource.mipLevels = 1;
        resource.arrayLayers = 1;
        resource.usage = 0;
        static sl::SubresourceRange range{};
        range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        range.levelCount = 1;
        range.layerCount = 1;
        resource.next = &range;
    }
    resource.flags = 0;
    const sl::Extent extent{0, 0, g_state.renderWidth, g_state.renderHeight};
    return sl::ResourceTag(&resource, sl::kBufferTypeDisocclusionMask,
                           sl::ResourceLifecycle::eValidUntilPresent, &extent);
}

sl::ResourceTag make_rr_responsivity_mask_tag(sl::Resource& resource) noexcept {
    resource = sl::Resource{};
    // RR Guide 3.4.14: responsivity is a signed R8_SNORM/R16_SFLOAT signal,
    // distinct from the UNORM bias/disocclusion mask. This ABI carries no such
    // guide; clear Tag 68 every frame rather than aliasing an incompatible mask.
    resource.flags = 0;
    const sl::Extent extent{0, 0, g_state.renderWidth, g_state.renderHeight};
    return sl::ResourceTag(&resource, sl::kBufferTypeResponsivityMask,
                           sl::ResourceLifecycle::eValidUntilPresent, &extent);
}

ImageTransitionDesc rr_bias_current_color_transition(const VkImageLayout entryLayout,
                                                     const bool restore) noexcept {
    if (!g_state.rrArmed || !valid_image(g_state.rrBiasCurrentColor)) return {};
    constexpr VkPipelineStageFlags stages = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    constexpr VkAccessFlags access = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    return {from_uint64<VkImage>(g_state.rrBiasCurrentColor.image), image_range_of(false),
            restore ? kDlssInputLayout : entryLayout,
            restore ? entryLayout : kDlssInputLayout, stages, stages, access, access};
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
    *this = FrameEligibility{};
}

void MarkerLog::record(const uint32_t type, const sl::FrameToken* frameToken) noexcept {
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
    if (eventCount == 0) {
        return kNotInitialized;
    }
    for (uint32_t i = 0; i < typeCount && i < kMarkerTypeCapacity; ++i) {
        outTypeCounts[i] = typeCounts[i];
    }
    *outEventCount = eventCount;
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
        vkDeviceWaitIdle(g_state.device);
    }
}

void record_batched_transitions(const VkCommandBuffer commandBuffer,
                                const ImageTransitionDesc* transitions,
                                const uint32_t count) noexcept {
    if (commandBuffer == VK_NULL_HANDLE || transitions == nullptr || count == 0) {
        return;
    }

    constexpr uint32_t kStackCapacity = 16;
    VkImageMemoryBarrier stackBarriers[kStackCapacity]{};
    std::vector<VkImageMemoryBarrier> heapBarriers;
    VkImageMemoryBarrier* barriers = stackBarriers;
    if (count > kStackCapacity) {
        heapBarriers.resize(count);
        barriers = heapBarriers.data();
    }

    uint32_t barrierCount = 0;
    VkPipelineStageFlags combinedSrcStage = 0;
    VkPipelineStageFlags combinedDstStage = 0;

    for (uint32_t i = 0; i < count; ++i) {
        const auto& desc = transitions[i];
        if (desc.image == VK_NULL_HANDLE) {
            continue;
        }

        const bool hasLayoutChange = (desc.oldLayout != desc.newLayout);
        const bool hasMemoryDependency = (desc.srcAccess != 0 || desc.dstAccess != 0);
        const bool hasExecutionDependency = (desc.srcStage != 0 && desc.dstStage != 0);

        if (!hasLayoutChange && !hasMemoryDependency && !hasExecutionDependency) {
            continue;
        }

        VkImageMemoryBarrier& barrier = barriers[barrierCount++];
        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.pNext = nullptr;
        barrier.srcAccessMask = desc.srcAccess;
        barrier.dstAccessMask = desc.dstAccess;
        barrier.oldLayout = desc.oldLayout;
        barrier.newLayout = desc.newLayout;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.image = desc.image;
        barrier.subresourceRange = desc.subresourceRange;

        combinedSrcStage |= desc.srcStage;
        combinedDstStage |= desc.dstStage;
    }

    if (barrierCount > 0) {
        if (combinedSrcStage == 0) combinedSrcStage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        if (combinedDstStage == 0) combinedDstStage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;

        vkCmdPipelineBarrier(commandBuffer, combinedSrcStage, combinedDstStage, 0,
                             0, nullptr, 0, nullptr, barrierCount, barriers);
    }
}

void record_layout_transition(const VkCommandBuffer commandBuffer, const VkImage image,
                              const VkImageSubresourceRange& subresourceRange,
                              const VkImageLayout oldLayout,
                              const VkImageLayout newLayout) noexcept {
    if (oldLayout == newLayout) {
        return;
    }
    const ImageTransitionDesc desc{
        image,
        subresourceRange,
        oldLayout,
        newLayout,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
        VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT
    };
    record_batched_transitions(commandBuffer, &desc, 1);
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
    if (g_state.rrOutputImage.image != VK_NULL_HANDLE &&
        image == to_uint64(g_state.rrOutputImage.image)) {
        return g_state.rrOutputImage.layout;
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
    } else if (g_state.rrOutputImage.image != VK_NULL_HANDLE &&
               image == to_uint64(g_state.rrOutputImage.image)) {
        g_state.rrOutputImage.layout = layout;
    }
}

}
