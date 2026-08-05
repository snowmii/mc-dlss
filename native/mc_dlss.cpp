#include "mc_dlss.h"

#include <vulkan/vulkan.h>

#include <nvsdk_ngx.h>
#include <nvsdk_ngx_helpers_vk.h>
#include <nvsdk_ngx_vk.h>

#include <cstdint>
#include <cstring>
#include <limits>
#include <mutex>
#include <string>
#include <type_traits>
#include <utility>

#if defined(_WIN32)
#include <windows.h>
#else
#include <codecvt>
#include <locale>
#endif

namespace {

constexpr char kProjectId[] = "50f68c51-c7be-49bd-a875-f73045f88d27";
constexpr char kEngineVersion[] = "Minecraft 26.2";
constexpr int32_t kSuccess = static_cast<int32_t>(NVSDK_NGX_Result_Success);
constexpr int32_t kFailure = static_cast<int32_t>(NVSDK_NGX_Result_Fail);
constexpr int32_t kInvalidParameter = static_cast<int32_t>(NVSDK_NGX_Result_FAIL_InvalidParameter);
constexpr int32_t kNotInitialized = static_cast<int32_t>(NVSDK_NGX_Result_FAIL_NotInitialized);
constexpr int32_t kFeatureNotSupported = static_cast<int32_t>(NVSDK_NGX_Result_FAIL_FeatureNotSupported);

struct DlssState {
    bool initialized = false;
    bool bootstrapComplete = false;
    uint64_t instanceValue = 0;
    uint64_t physicalDeviceValue = 0;
    uint64_t deviceValue = 0;
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    std::wstring sdkPath;
    std::wstring dataPath;
    NVSDK_NGX_Parameter* capabilityParameters = nullptr;
    NVSDK_NGX_Handle* feature = nullptr;
    uint32_t outputWidth = 0;
    uint32_t outputHeight = 0;
    uint32_t renderWidth = 0;
    uint32_t renderHeight = 0;
    uint32_t qualityMode = 0;
    uint32_t featureOutputWidth = 0;
    uint32_t featureOutputHeight = 0;
    uint32_t featureRenderWidth = 0;
    uint32_t featureRenderHeight = 0;
    uint32_t featureQualityMode = 0;
};

DlssState g_state;
std::mutex g_mutex;

template <typename VulkanHandle>
VulkanHandle from_uint64(const uint64_t value) noexcept {
    if constexpr (std::is_pointer<VulkanHandle>::value) {
        return reinterpret_cast<VulkanHandle>(static_cast<std::uintptr_t>(value));
    } else {
        return static_cast<VulkanHandle>(value);
    }
}

bool utf8_to_wide(const char* input, std::wstring& output) {
    if (input == nullptr || input[0] == '\0') {
        return false;
    }
#if defined(_WIN32)
    const size_t size = std::strlen(input);
    if (size > static_cast<size_t>(std::numeric_limits<int>::max())) {
        return false;
    }
    const int required = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, input,
                                              static_cast<int>(size), nullptr, 0);
    if (required <= 0) {
        return false;
    }
    output.resize(static_cast<size_t>(required));
    return MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, input,
                               static_cast<int>(size), output.data(), required) == required;
#else
    try {
        output = std::wstring_convert<std::codecvt_utf8<wchar_t>>().from_bytes(input);
        return !output.empty();
    } catch (...) {
        return false;
    }
#endif
}

bool valid_quality_mode(const uint32_t qualityMode) noexcept {
    return qualityMode <= static_cast<uint32_t>(NVSDK_NGX_PerfQuality_Value_MaxQuality);
}

bool valid_dimensions(const uint32_t outputWidth, const uint32_t outputHeight,
                      const uint32_t renderWidth, const uint32_t renderHeight) noexcept {
    return outputWidth != 0 && outputHeight != 0 && renderWidth != 0 && renderHeight != 0 &&
           renderWidth <= outputWidth && renderHeight <= outputHeight;
}

void reset_state() noexcept {
    g_state = DlssState{};
}

int32_t release_feature() noexcept {
    if (g_state.feature == nullptr) {
        return kSuccess;
    }
    const int32_t result = static_cast<int32_t>(
        NVSDK_NGX_VULKAN_ReleaseFeature(g_state.feature));
    if (result == kSuccess) {
        g_state.feature = nullptr;
    }
    return result;
}

int32_t destroy_capability_parameters() noexcept {
    if (g_state.capabilityParameters == nullptr) {
        return kSuccess;
    }
    const int32_t result = static_cast<int32_t>(
        NVSDK_NGX_VULKAN_DestroyParameters(g_state.capabilityParameters));
    if (result == kSuccess) {
        g_state.capabilityParameters = nullptr;
    }
    return result;
}

int32_t shutdown_state() noexcept {
    g_state.bootstrapComplete = false;
    // Feature must die before its parameters and NGX device state.
    int32_t result = release_feature();
    if (result != kSuccess) {
        return result;
    }
    result = destroy_capability_parameters();
    if (result != kSuccess) {
        return result;
    }
    if (g_state.initialized) {
        result = static_cast<int32_t>(NVSDK_NGX_VULKAN_Shutdown1(g_state.device));
        if (result != kSuccess) {
            return result;
        }
    }
    reset_state();
    return kSuccess;
}

int32_t cleanup_after_initialize_failure(const int32_t primaryFailure) noexcept {
    const int32_t cleanupResult = shutdown_state();
    return cleanupResult == kSuccess ? primaryFailure : cleanupResult;
}

struct DlssImageResourceInput {
    uint64_t imageView;
    uint64_t image;
    uint32_t format;
    uint32_t aspectMask;
    uint32_t baseMipLevel;
    uint32_t levelCount;
    uint32_t baseArrayLayer;
    uint32_t layerCount;
};

bool valid_image_resource(const DlssImageResourceInput& resource) noexcept {
    return resource.imageView != 0 && resource.image != 0 &&
           resource.format != static_cast<uint32_t>(VK_FORMAT_UNDEFINED) &&
           resource.aspectMask != 0 && resource.levelCount != 0 && resource.layerCount != 0;
}

NVSDK_NGX_Resource_VK make_image_view_resource(const DlssImageResourceInput& resource,
                                                const uint32_t width,
                                                const uint32_t height,
                                                const bool readWrite) noexcept {
    const VkImageSubresourceRange subresourceRange{
        static_cast<VkImageAspectFlags>(resource.aspectMask),
        resource.baseMipLevel,
        resource.levelCount,
        resource.baseArrayLayer,
        resource.layerCount,
    };
    return NVSDK_NGX_Create_ImageView_Resource_VK(
        from_uint64<VkImageView>(resource.imageView), from_uint64<VkImage>(resource.image),
        subresourceRange, static_cast<VkFormat>(resource.format), width, height, readWrite);
}

int32_t query_optimal_dimensions(const uint32_t outputWidth, const uint32_t outputHeight,
                                 const uint32_t qualityMode, uint32_t* renderWidth,
                                 uint32_t* renderHeight) noexcept {
    if (!g_state.bootstrapComplete || g_state.capabilityParameters == nullptr) {
        return kNotInitialized;
    }
    if (renderWidth == nullptr || renderHeight == nullptr || outputWidth == 0 ||
        outputHeight == 0 || !valid_quality_mode(qualityMode)) {
        return kInvalidParameter;
    }

    void* callback = nullptr;
    NVSDK_NGX_Result result = NVSDK_NGX_Parameter_GetVoidPointer(
        g_state.capabilityParameters, NVSDK_NGX_Parameter_DLSSOptimalSettingsCallback, &callback);
    if (result != NVSDK_NGX_Result_Success) {
        return static_cast<int32_t>(result);
    }
    if (callback == nullptr) {
        return static_cast<int32_t>(NVSDK_NGX_Result_FAIL_OutOfDate);
    }

    // SDK 310.7.0 setters are void; only the callback and getters report results.
    NVSDK_NGX_Parameter_SetUI(g_state.capabilityParameters, NVSDK_NGX_Parameter_Width,
                               outputWidth);
    NVSDK_NGX_Parameter_SetUI(g_state.capabilityParameters, NVSDK_NGX_Parameter_Height,
                               outputHeight);
    NVSDK_NGX_Parameter_SetI(g_state.capabilityParameters,
                              NVSDK_NGX_Parameter_PerfQualityValue,
                              static_cast<int>(qualityMode));
    NVSDK_NGX_Parameter_SetI(g_state.capabilityParameters, NVSDK_NGX_Parameter_RTXValue, 0);

    const auto getOptimalSettings =
        reinterpret_cast<PFN_NVSDK_NGX_DLSS_GetOptimalSettingsCallback>(callback);
    result = getOptimalSettings(g_state.capabilityParameters);
    if (result != NVSDK_NGX_Result_Success) {
        return static_cast<int32_t>(result);
    }
    uint32_t queriedRenderWidth = 0;
    uint32_t queriedRenderHeight = 0;
    result = NVSDK_NGX_Parameter_GetUI(g_state.capabilityParameters,
                                        NVSDK_NGX_Parameter_OutWidth,
                                        &queriedRenderWidth);
    if (result != NVSDK_NGX_Result_Success) {
        return static_cast<int32_t>(result);
    }
    result = NVSDK_NGX_Parameter_GetUI(g_state.capabilityParameters,
                                        NVSDK_NGX_Parameter_OutHeight,
                                        &queriedRenderHeight);
    if (result != NVSDK_NGX_Result_Success) {
        return static_cast<int32_t>(result);
    }
    if (!valid_dimensions(outputWidth, outputHeight, queriedRenderWidth, queriedRenderHeight)) {
        return kInvalidParameter;
    }
    *renderWidth = queriedRenderWidth;
    *renderHeight = queriedRenderHeight;
    return kSuccess;
}

} // namespace

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_initialize(const uint64_t vk_instance,
                                                     const uint64_t vk_physical_device,
                                                     const uint64_t vk_device,
                                                     const char* sdk_path,
                                                     const char* data_path) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (vk_instance == 0 || vk_physical_device == 0 || vk_device == 0) {
            return kInvalidParameter;
        }
        std::wstring sdkPath;
        std::wstring dataPath;
        if (!utf8_to_wide(sdk_path, sdkPath) || !utf8_to_wide(data_path, dataPath)) {
            return kInvalidParameter;
        }

        if (g_state.initialized) {
            const bool sameDevice = g_state.instanceValue == vk_instance &&
                                    g_state.physicalDeviceValue == vk_physical_device &&
                                    g_state.deviceValue == vk_device &&
                                    g_state.sdkPath == sdkPath && g_state.dataPath == dataPath;
            if (!sameDevice) {
                // A different device/path cannot replace live NGX ownership without close.
                return kInvalidParameter;
            }
            if (g_state.bootstrapComplete) {
                return kSuccess;
            }
            // A prior bootstrap failed during cleanup. Retry only after releasing every
            // ownership still held by this state.
            const int32_t cleanupResult = shutdown_state();
            if (cleanupResult != kSuccess) {
                return cleanupResult;
            }
        }

        g_state.instanceValue = vk_instance;
        g_state.physicalDeviceValue = vk_physical_device;
        g_state.deviceValue = vk_device;
        g_state.instance = from_uint64<VkInstance>(vk_instance);
        g_state.physicalDevice = from_uint64<VkPhysicalDevice>(vk_physical_device);
        g_state.device = from_uint64<VkDevice>(vk_device);
        g_state.sdkPath = std::move(sdkPath);
        g_state.dataPath = std::move(dataPath);

        const wchar_t* featureSearchPath = g_state.sdkPath.c_str();
        NVSDK_NGX_FeatureCommonInfo featureInfo{};
        featureInfo.PathListInfo.Path = &featureSearchPath;
        featureInfo.PathListInfo.Length = 1;

        const NVSDK_NGX_Result initResult = NVSDK_NGX_VULKAN_Init_with_ProjectID(
            kProjectId, NVSDK_NGX_ENGINE_TYPE_CUSTOM, kEngineVersion, g_state.dataPath.c_str(),
            g_state.instance, g_state.physicalDevice, g_state.device, nullptr, nullptr,
            &featureInfo, NVSDK_NGX_Version_API);
        if (initResult != NVSDK_NGX_Result_Success) {
            reset_state();
            return static_cast<int32_t>(initResult);
        }
        g_state.initialized = true;

        NVSDK_NGX_Result result =
            NVSDK_NGX_VULKAN_GetCapabilityParameters(&g_state.capabilityParameters);
        if (result != NVSDK_NGX_Result_Success) {
            return cleanup_after_initialize_failure(static_cast<int32_t>(result));
        }
        if (g_state.capabilityParameters == nullptr) {
            return cleanup_after_initialize_failure(kInvalidParameter);
        }

        int available = 0;
        result = NVSDK_NGX_Parameter_GetI(g_state.capabilityParameters,
                                          NVSDK_NGX_Parameter_SuperSampling_Available,
                                          &available);
        if (result != NVSDK_NGX_Result_Success) {
            return cleanup_after_initialize_failure(static_cast<int32_t>(result));
        }
        if (available <= 0) {
            return cleanup_after_initialize_failure(kFeatureNotSupported);
        }
        g_state.bootstrapComplete = true;
        return kSuccess;
    } catch (...) {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_state.initialized || g_state.capabilityParameters != nullptr) {
            const int32_t cleanupResult = shutdown_state();
            return cleanupResult == kSuccess ? kFailure : cleanupResult;
        }
        reset_state();
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_optimal_dimensions(
    const uint32_t output_width, const uint32_t output_height, const uint32_t quality_mode,
    uint32_t* render_width, uint32_t* render_height) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return query_optimal_dimensions(output_width, output_height, quality_mode, render_width,
                                        render_height);
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_configure(const uint32_t output_width,
                                                    const uint32_t output_height,
                                                    const uint32_t render_width,
                                                    const uint32_t render_height,
                                                    const uint32_t quality_mode) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_state.bootstrapComplete) {
            return kNotInitialized;
        }
        if (!valid_dimensions(output_width, output_height, render_width, render_height) ||
            !valid_quality_mode(quality_mode)) {
            return kInvalidParameter;
        }
        g_state.outputWidth = output_width;
        g_state.outputHeight = output_height;
        g_state.renderWidth = render_width;
        g_state.renderHeight = render_height;
        g_state.qualityMode = quality_mode;
        return kSuccess;
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_evaluate(
    const uint64_t command_buffer, const uint64_t color_view, const uint64_t color_image,
    const uint32_t color_format, const uint32_t color_aspect_mask,
    const uint32_t color_base_mip_level, const uint32_t color_level_count,
    const uint32_t color_base_array_layer, const uint32_t color_layer_count,
    const uint64_t depth_view, const uint64_t depth_image, const uint32_t depth_format,
    const uint32_t depth_aspect_mask, const uint32_t depth_base_mip_level,
    const uint32_t depth_level_count, const uint32_t depth_base_array_layer,
    const uint32_t depth_layer_count, const uint64_t motion_view, const uint64_t motion_image,
    const uint32_t motion_format, const uint32_t motion_aspect_mask,
    const uint32_t motion_base_mip_level, const uint32_t motion_level_count,
    const uint32_t motion_base_array_layer, const uint32_t motion_layer_count,
    const uint64_t output_view, const uint64_t output_image, const uint32_t output_format,
    const uint32_t output_aspect_mask, const uint32_t output_base_mip_level,
    const uint32_t output_level_count, const uint32_t output_base_array_layer,
    const uint32_t output_layer_count, const uint32_t render_width,
    const uint32_t render_height, const uint32_t output_width, const uint32_t output_height,
    const float jitter_x, const float jitter_y, const float motion_scale_x,
    const float motion_scale_y, const float frame_time_milliseconds,
    const int32_t reset_history) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_state.bootstrapComplete) {
            return kNotInitialized;
        }
        if (command_buffer == 0 || reset_history < 0 || reset_history > 1 ||
            !valid_dimensions(output_width, output_height, render_width, render_height) ||
            output_width != g_state.outputWidth || output_height != g_state.outputHeight ||
            render_width != g_state.renderWidth || render_height != g_state.renderHeight) {
            return kInvalidParameter;
        }

        const DlssImageResourceInput colorResourceInput{
            color_view, color_image, color_format, color_aspect_mask, color_base_mip_level,
            color_level_count, color_base_array_layer, color_layer_count};
        const DlssImageResourceInput depthResourceInput{
            depth_view, depth_image, depth_format, depth_aspect_mask, depth_base_mip_level,
            depth_level_count, depth_base_array_layer, depth_layer_count};
        const DlssImageResourceInput motionResourceInput{
            motion_view, motion_image, motion_format, motion_aspect_mask, motion_base_mip_level,
            motion_level_count, motion_base_array_layer, motion_layer_count};
        const DlssImageResourceInput outputResourceInput{
            output_view, output_image, output_format, output_aspect_mask, output_base_mip_level,
            output_level_count, output_base_array_layer, output_layer_count};
        if (!valid_image_resource(colorResourceInput) || !valid_image_resource(depthResourceInput) ||
            !valid_image_resource(motionResourceInput) || !valid_image_resource(outputResourceInput)) {
            return kInvalidParameter;
        }

        auto colorResource = make_image_view_resource(
            colorResourceInput, render_width, render_height, false);
        auto depthResource = make_image_view_resource(
            depthResourceInput, render_width, render_height, false);
        auto motionResource = make_image_view_resource(
            motionResourceInput, render_width, render_height, false);
        auto outputResource = make_image_view_resource(
            outputResourceInput, output_width, output_height, true);
        const bool featureMatchesConfiguration =
            g_state.feature != nullptr &&
            g_state.featureOutputWidth == output_width &&
            g_state.featureOutputHeight == output_height &&
            g_state.featureRenderWidth == render_width &&
            g_state.featureRenderHeight == render_height &&
            g_state.featureQualityMode == g_state.qualityMode;
        if (!featureMatchesConfiguration) {
            int32_t result = release_feature();
            if (result != kSuccess) {
                return result;
            }
            NVSDK_NGX_DLSS_Create_Params createParams{};
            createParams.Feature.InWidth = render_width;
            createParams.Feature.InHeight = render_height;
            createParams.Feature.InTargetWidth = output_width;
            createParams.Feature.InTargetHeight = output_height;
            createParams.Feature.InPerfQualityValue =
                static_cast<NVSDK_NGX_PerfQuality_Value>(g_state.qualityMode);
            createParams.InFeatureCreateFlags =
                NVSDK_NGX_DLSS_Feature_Flags_MVLowRes |
                NVSDK_NGX_DLSS_Feature_Flags_DepthInverted;
            const NVSDK_NGX_Result createResult = NGX_VULKAN_CREATE_DLSS_EXT(
                from_uint64<VkCommandBuffer>(command_buffer), 1, 1, &g_state.feature,
                g_state.capabilityParameters, &createParams);
            if (createResult != NVSDK_NGX_Result_Success) {
                g_state.feature = nullptr;
                return static_cast<int32_t>(createResult);
            }
            g_state.featureOutputWidth = output_width;
            g_state.featureOutputHeight = output_height;
            g_state.featureRenderWidth = render_width;
            g_state.featureRenderHeight = render_height;
            g_state.featureQualityMode = g_state.qualityMode;
        }

        NVSDK_NGX_VK_DLSS_Eval_Params evaluateParams{};
        evaluateParams.Feature.pInColor = &colorResource;
        evaluateParams.Feature.pInOutput = &outputResource;
        evaluateParams.pInDepth = &depthResource;
        evaluateParams.pInMotionVectors = &motionResource;
        evaluateParams.InJitterOffsetX = jitter_x;
        evaluateParams.InJitterOffsetY = jitter_y;
        evaluateParams.InRenderSubrectDimensions = {render_width, render_height};
        evaluateParams.InReset = reset_history;
        evaluateParams.InMVScaleX = motion_scale_x;
        evaluateParams.InMVScaleY = motion_scale_y;
        evaluateParams.InFrameTimeDeltaInMsec = frame_time_milliseconds;
        return static_cast<int32_t>(NGX_VULKAN_EVALUATE_DLSS_EXT(
            from_uint64<VkCommandBuffer>(command_buffer), g_state.feature,
            g_state.capabilityParameters, &evaluateParams));
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_reset(void) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_state.bootstrapComplete) {
            return kNotInitialized;
        }
        return release_feature();
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_close(void) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_state.initialized && g_state.capabilityParameters == nullptr) {
            return kSuccess;
        }
        return shutdown_state();
    } catch (...) {
        return kFailure;
    }
}
