#include "mc_dlss.h"

#include <vulkan/vulkan.h>

#include <nvsdk_ngx.h>
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
    // Parameters are owned by this bridge and must die before NGX shutdown.
    int32_t result = destroy_capability_parameters();
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
    // Keep existing lifecycle ABI usable without allocating feature parameters.
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_state.bootstrapComplete) {
            return kNotInitialized;
        }
        return valid_dimensions(output_width, output_height, render_width, render_height) &&
                       valid_quality_mode(quality_mode)
                   ? kSuccess
                   : kInvalidParameter;
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_evaluate(const uint64_t, const uint64_t, const uint64_t,
                                                   const uint64_t, const uint64_t, const uint32_t,
                                                   const uint32_t, const uint32_t, const uint32_t,
                                                   const float, const float, const float, const float,
                                                   const float, const int32_t) {
    // ABI remains stable; feature evaluation belongs to later renderer slice.
    return kFeatureNotSupported;
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_reset(void) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return g_state.bootstrapComplete ? kSuccess : kNotInitialized;
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
