#ifndef MC_DLSS_INTERNAL_IMAGES_H
#define MC_DLSS_INTERNAL_IMAGES_H

#include "internal/state.h"

namespace mc_dlss {

void destroy_owned_image(DlssOwnedImage& owned) noexcept;

int32_t create_owned_image(uint32_t width, uint32_t height, VkFormat format,
                           VkImageUsageFlags usage, VkImageAspectFlags aspect,
                           DlssOwnedImage& owned) noexcept;

int32_t acquire_images() noexcept;

void release_images() noexcept;

}

#endif
