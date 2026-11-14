#ifndef MC_DLSS_INTERNAL_COMMON_H
#define MC_DLSS_INTERNAL_COMMON_H

#include "mc_dlss.h"

#include <vulkan/vulkan.h>

#include <nvsdk_ngx.h>

#include <cstdint>
#include <string>
#include <type_traits>

namespace mc_dlss {

constexpr int32_t kSuccess = static_cast<int32_t>(NVSDK_NGX_Result_Success);
constexpr int32_t kFailure = static_cast<int32_t>(NVSDK_NGX_Result_Fail);
constexpr int32_t kInvalidParameter = static_cast<int32_t>(NVSDK_NGX_Result_FAIL_InvalidParameter);
constexpr int32_t kNotInitialized = static_cast<int32_t>(NVSDK_NGX_Result_FAIL_NotInitialized);

constexpr VkFormat kMotionFormat = VK_FORMAT_R16G16_SFLOAT;
constexpr VkFormat kOutputFormat = VK_FORMAT_R8G8B8A8_UNORM;
constexpr VkFormat kRrOutputFormat = VK_FORMAT_R16G16B16A16_SFLOAT;

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

bool valid_image(const McDlssImage& image) noexcept;

bool valid_rr_bias_current_color(const McDlssImage& image) noexcept;

VkImageSubresourceRange image_range_of(bool isDepth) noexcept;
VkImageSubresourceRange depth_range_of(const McDlssImage& image) noexcept;

}

#endif
