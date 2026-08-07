#include "internal/images.h"

namespace mc_dlss {
namespace {

bool find_memory_type(const uint32_t typeBits, const VkMemoryPropertyFlags properties,
                      uint32_t* index) noexcept {
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    vkGetPhysicalDeviceMemoryProperties(g_state.physicalDevice, &memoryProperties);
    for (uint32_t candidate = 0; candidate < memoryProperties.memoryTypeCount; ++candidate) {
        const bool allowed = (typeBits & (1u << candidate)) != 0;
        const VkMemoryPropertyFlags flags = memoryProperties.memoryTypes[candidate].propertyFlags;
        if (allowed && (flags & properties) == properties) {
            *index = candidate;
            return true;
        }
    }
    return false;
}

} // namespace

void destroy_owned_image(DlssOwnedImage& owned) noexcept {
    if (g_state.device != VK_NULL_HANDLE) {
        if (owned.view != VK_NULL_HANDLE) {
            vkDestroyImageView(g_state.device, owned.view, nullptr);
        }
        if (owned.image != VK_NULL_HANDLE) {
            vkDestroyImage(g_state.device, owned.image, nullptr);
        }
        if (owned.memory != VK_NULL_HANDLE) {
            vkFreeMemory(g_state.device, owned.memory, nullptr);
        }
    }
    owned = DlssOwnedImage{};
}

int32_t create_owned_image(const uint32_t width, const uint32_t height, const VkFormat format,
                           const VkImageUsageFlags usage, DlssOwnedImage& owned) noexcept {
    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = format;
    imageInfo.extent = VkExtent3D{width, height, 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = usage;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    // Every handle is created into a local and published to `owned` only once the call
    // succeeded: Vulkan leaves the output undefined on failure, and a half-written
    // struct would hand the destroy path a garbage handle to free.
    VkImage image = VK_NULL_HANDLE;
    if (vkCreateImage(g_state.device, &imageInfo, nullptr, &image) != VK_SUCCESS) {
        return kFailure;
    }
    owned.image = image;

    VkMemoryRequirements requirements{};
    vkGetImageMemoryRequirements(g_state.device, image, &requirements);
    uint32_t memoryTypeIndex = 0;
    if (!find_memory_type(requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                          &memoryTypeIndex)) {
        destroy_owned_image(owned);
        return kFailure;
    }

    VkMemoryAllocateInfo allocateInfo{};
    allocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocateInfo.allocationSize = requirements.size;
    allocateInfo.memoryTypeIndex = memoryTypeIndex;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    if (vkAllocateMemory(g_state.device, &allocateInfo, nullptr, &memory) != VK_SUCCESS) {
        destroy_owned_image(owned);
        return kFailure;
    }
    owned.memory = memory;
    if (vkBindImageMemory(g_state.device, image, memory, 0) != VK_SUCCESS) {
        destroy_owned_image(owned);
        return kFailure;
    }

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = format;
    viewInfo.subresourceRange = VkImageSubresourceRange{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VkImageView view = VK_NULL_HANDLE;
    if (vkCreateImageView(g_state.device, &viewInfo, nullptr, &view) != VK_SUCCESS) {
        destroy_owned_image(owned);
        return kFailure;
    }
    owned.view = view;
    return kSuccess;
}

int32_t acquire_images() noexcept {
    // Dimensions come from the last configure and nowhere else, so there is no
    // second source of truth to disagree with the feature's own size.
    if (!valid_dimensions(g_state.outputWidth, g_state.outputHeight, g_state.renderWidth,
                          g_state.renderHeight)) {
        return kInvalidParameter;
    }

    const bool matchesConfiguration = g_state.motionImage.view != VK_NULL_HANDLE &&
                                      g_state.outputImage.view != VK_NULL_HANDLE &&
                                      g_state.imagesRenderWidth == g_state.renderWidth &&
                                      g_state.imagesRenderHeight == g_state.renderHeight &&
                                      g_state.imagesOutputWidth == g_state.outputWidth &&
                                      g_state.imagesOutputHeight == g_state.outputHeight;
    if (matchesConfiguration) {
        return kSuccess;
    }

    release_images();
    // Motion is written by the engine and read by DLSS; output is written by
    // DLSS and copied into Minecraft's target.
    int32_t result = create_owned_image(
        g_state.renderWidth, g_state.renderHeight, kMotionFormat,
        VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
            VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT |
            VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
        g_state.motionImage);
    if (result != kSuccess) {
        release_images();
        return result;
    }
    result = create_owned_image(
        g_state.outputWidth, g_state.outputHeight, kOutputFormat,
        VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
        g_state.outputImage);
    if (result != kSuccess) {
        release_images();
        return result;
    }
    g_state.imagesRenderWidth = g_state.renderWidth;
    g_state.imagesRenderHeight = g_state.renderHeight;
    g_state.imagesOutputWidth = g_state.outputWidth;
    g_state.imagesOutputHeight = g_state.outputHeight;
    return kSuccess;
}

void release_images() noexcept {
    if (g_state.motionImage.image != VK_NULL_HANDLE ||
        g_state.outputImage.image != VK_NULL_HANDLE) {
        wait_device_idle();
    }
    destroy_owned_image(g_state.motionImage);
    destroy_owned_image(g_state.outputImage);
    // A destroyed view's handle value can be handed back out by the next creation, so the
    // motion pass must forget what its descriptors describe rather than compare handles
    // against a view that no longer exists.
    g_state.motionPass.boundSet = -1;
    g_state.motionPass.boundDepthView = 0;
    g_state.motionPass.boundMotionView = 0;
    g_state.imagesRenderWidth = 0;
    g_state.imagesRenderHeight = 0;
    g_state.imagesOutputWidth = 0;
    g_state.imagesOutputHeight = 0;
}

} // namespace mc_dlss
