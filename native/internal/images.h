#ifndef MC_DLSS_INTERNAL_IMAGES_H
#define MC_DLSS_INTERNAL_IMAGES_H

#include "internal/state.h"

/*
 * The two images this module allocates for itself: the motion image the engine's depth is
 * reprojected into, and the output image DLSS writes its upscaled frame into.
 *
 * Both are sized from the configuration and owned here for the life of that configuration.
 * They are the reason the evaluation carries only the engine's colour and depth: nothing
 * outside this unit has to hold a handle to either.
 */
namespace mc_dlss {

// Destroys in the reverse of creation order: the view reads the image, the image
// owns nothing, and the memory outlives neither.
void destroy_owned_image(DlssOwnedImage& owned) noexcept;

// Any failure here destroys whatever it already made, so a caller never has to
// distinguish "not created" from "half created".
int32_t create_owned_image(uint32_t width, uint32_t height, VkFormat format,
                           VkImageUsageFlags usage, DlssOwnedImage& owned) noexcept;

// Allocates both images for the configuration currently stored, reusing them when that
// configuration is unchanged. Partial failure leaves nothing allocated.
int32_t acquire_images() noexcept;

void release_images() noexcept;

} // namespace mc_dlss

#endif
