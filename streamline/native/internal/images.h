#ifndef MC_DLSS_INTERNAL_IMAGES_H
#define MC_DLSS_INTERNAL_IMAGES_H

#include "internal/state.h"

/*
 * The six images this module allocates for itself: the motion image the engine's depth is
 * reprojected into, the output image DLSS writes its upscaled frame into, and the four FG
 * orientation copies the DLSS-G tag names.
 *
 * All are sized from the configuration and owned here for the life of that configuration.
 * They are the reason the evaluation carries only the engine's colour and depth: nothing
 * outside this unit has to hold a handle to either.
 *
 * The FG copies exist because DLSS-G consumes the frame in the backbuffer's orientation,
 * the vertical mirror of the engine's: the depth (render-sized) and the HUD-less and UI
 * buffers (output-sized) are filled by y-inverting blits, and the motion copy by the
 * motion dispatches' flipped write. The SR viewport keeps the engine-oriented originals;
 * no engine image is ever written or relabeled for the copies.
 */
namespace mc_dlss {

// Destroys in the reverse of creation order: the view reads the image, the image
// owns nothing, and the memory outlives neither.
void destroy_owned_image(DlssOwnedImage& owned) noexcept;

// Any failure here destroys whatever it already made, so a caller never has to
// distinguish "not created" from "half created". `aspect` is the aspect the image's
// view carries: colour for every colour image, depth for the FG depth copy.
int32_t create_owned_image(uint32_t width, uint32_t height, VkFormat format,
                           VkImageUsageFlags usage, VkImageAspectFlags aspect,
                           DlssOwnedImage& owned) noexcept;

// Allocates both images for the configuration currently stored, reusing them when that
// configuration is unchanged. Partial failure leaves nothing allocated.
int32_t acquire_images() noexcept;

void release_images() noexcept;

} // namespace mc_dlss

#endif
