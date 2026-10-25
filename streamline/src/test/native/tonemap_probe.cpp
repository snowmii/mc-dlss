#include "internal/tonemap.h"

using namespace mc_dlss;

// Test-only DLL linked with the production state, common, and tone-map object files.
extern "C" __declspec(dllexport) void tone_map_setup(uint64_t device, uint64_t hdrView,
                                                     uint64_t hdrImage, uint32_t width, uint32_t height) {
    g_state.device = from_uint64<VkDevice>(device);
    g_state.outputWidth = width;
    g_state.outputHeight = height;
    g_state.rrOutputImage.image = from_uint64<VkImage>(hdrImage);
    g_state.rrOutputImage.view = from_uint64<VkImageView>(hdrView);
    g_state.rrOutputImage.layout = VK_IMAGE_LAYOUT_GENERAL;
}

extern "C" __declspec(dllexport) int tone_map_record(uint64_t command, uint64_t outputView,
                                                      uint64_t outputImage, uint64_t reflectionView,
                                                      uint64_t reflectionImage, float exposure) {
    return record_tone_map(from_uint64<VkCommandBuffer>(command), exposure,
        McDlssImage{reflectionView, reflectionImage, VK_FORMAT_R16G16B16A16_SFLOAT},
        McDlssImage{outputView, outputImage, VK_FORMAT_R8G8B8A8_UNORM});
}

extern "C" __declspec(dllexport) void tone_map_copy(uint64_t command, uint64_t source,
                                                    uint64_t destination, uint32_t width, uint32_t height) {
    const VkCommandBuffer cmd = from_uint64<VkCommandBuffer>(command);
    const auto range = image_range_of(false);
    const ImageTransitionDesc before[] = {
        {from_uint64<VkImage>(source), range, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
         VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
         VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT},
        {from_uint64<VkImage>(destination), range, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
         VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
         VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_WRITE_BIT},
    };
    record_batched_transitions(cmd, before, 2);
    VkImageCopy region{};
    region.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.extent = {width, height, 1};
    vkCmdCopyImage(cmd, from_uint64<VkImage>(source), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                   from_uint64<VkImage>(destination), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
    const ImageTransitionDesc after[] = {
        {from_uint64<VkImage>(source), range, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
         VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
         VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT},
        {from_uint64<VkImage>(destination), range, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
         VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
         VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT},
    };
    record_batched_transitions(cmd, after, 2);
}

extern "C" __declspec(dllexport) void tone_map_close() {
    destroy_tone_map_pass();
    reset_state();
}
