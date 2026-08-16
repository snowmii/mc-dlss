#ifndef MC_DLSS_INTERNAL_COMMON_H
#define MC_DLSS_INTERNAL_COMMON_H

#include "mc_dlss.h"

#include <vulkan/vulkan.h>

#include <nvsdk_ngx.h>

#include <cstdint>
#include <string>
#include <type_traits>

/*
 * Bottom of the internal module graph: the result codes every unit returns, the handle
 * conversions every unit performs, and the validity rules shared by more than one of them.
 * Depends on nothing but the public header and the SDKs, which is what keeps the graph
 * acyclic - everything else in this directory is free to include it.
 */
namespace mc_dlss {

constexpr int32_t kSuccess = static_cast<int32_t>(NVSDK_NGX_Result_Success);
constexpr int32_t kFailure = static_cast<int32_t>(NVSDK_NGX_Result_Fail);
constexpr int32_t kInvalidParameter = static_cast<int32_t>(NVSDK_NGX_Result_FAIL_InvalidParameter);
constexpr int32_t kNotInitialized = static_cast<int32_t>(NVSDK_NGX_Result_FAIL_NotInitialized);

// R16G16_SFLOAT and R8G8B8A8_UNORM are both mandatory storage-image formats in
// Vulkan, so neither needs a runtime capability probe. Two half-float channels
// carry a screen-space motion vector at full precision, and the output matches
// Minecraft's RGBA8_UNORM main target so the copy back into it is a plain image
// copy rather than a conversion.
constexpr VkFormat kMotionFormat = VK_FORMAT_R16G16_SFLOAT;
constexpr VkFormat kOutputFormat = VK_FORMAT_R8G8B8A8_UNORM;

// Minecraft 26.2 rests every GpuTexture in VK_IMAGE_LAYOUT_GENERAL: VulkanGpuTexture
// transitions the freshly created image straight to it, VulkanCommandEncoder binds colour
// and depth attachments at it, and VulkanRenderPass binds sampled images at it. Nothing in
// the backend ever moves a texture anywhere else, so this is the layout the engine's colour
// and depth images arrive in and the one they must be handed back in.
constexpr VkImageLayout kEngineRestingLayout = VK_IMAGE_LAYOUT_GENERAL;
// DLSS requires its inputs in a read state ("Sample Image") and its output in a storage
// state, per the 310.7.0 programming guide's Resource States section, and restores both
// after the evaluation.
constexpr VkImageLayout kDlssInputLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
constexpr VkImageLayout kDlssOutputLayout = VK_IMAGE_LAYOUT_GENERAL;

template <typename VulkanHandle>
VulkanHandle from_uint64(const uint64_t value) noexcept {
    if constexpr (std::is_pointer<VulkanHandle>::value) {
        return reinterpret_cast<VulkanHandle>(static_cast<std::uintptr_t>(value));
    } else {
        return static_cast<VulkanHandle>(value);
    }
}

template <typename VulkanHandle>
uint64_t to_uint64(const VulkanHandle handle) noexcept {
    if constexpr (std::is_pointer<VulkanHandle>::value) {
        return static_cast<uint64_t>(reinterpret_cast<std::uintptr_t>(handle));
    } else {
        return static_cast<uint64_t>(handle);
    }
}

bool utf8_to_wide(const char* input, std::wstring& output) noexcept;

bool valid_dimensions(uint32_t outputWidth, uint32_t outputHeight, uint32_t renderWidth,
                      uint32_t renderHeight) noexcept;

// An image the caller carried across the ABI is usable only with all three fields present:
// a zero handle or an undefined format is a caller that did not fill the struct. The check
// here is structural only - the DLSS-G tag, whose roles fix the format against the recorded
// options, enforces those formats at its own call site.
bool valid_image(const McDlssImage& image) noexcept;

// Engine images are single-level, single-layer 2D images - Minecraft's Vulkan backend creates
// nothing else - and the module's own images are full-range, so every subresource range the
// evaluation touches is the same constant {0, 1, 0, 1}. The only thing that varies is the
// aspect, which follows from the image's role: colour, motion, and output are colour images,
// depth is a depth image. The ABI used to carry all five fields per image and every producer
// sent these exact values; deriving them here is what keeps the invariant in one place.
VkImageSubresourceRange image_range_of(bool isDepth) noexcept;

} // namespace mc_dlss

#endif
