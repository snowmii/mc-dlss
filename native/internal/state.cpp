#include "internal/state.h"

namespace mc_dlss {

DlssState g_state;
std::mutex g_mutex;

void reset_state() noexcept {
    g_state = DlssState{};
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
