#include "internal/ngx.h"

#include <nvsdk_ngx_helpers_vk.h>
#include <nvsdk_ngx_vk.h>

#include <cstring>

namespace mc_dlss {

bool valid_quality_mode(const uint32_t qualityMode) noexcept {
    switch (static_cast<NVSDK_NGX_PerfQuality_Value>(qualityMode)) {
        case NVSDK_NGX_PerfQuality_Value_MaxPerf:
        case NVSDK_NGX_PerfQuality_Value_Balanced:
        case NVSDK_NGX_PerfQuality_Value_MaxQuality:
        case NVSDK_NGX_PerfQuality_Value_UltraPerformance:
        case NVSDK_NGX_PerfQuality_Value_DLAA:
            return true;
        // UltraQuality sits between the two the SDK defines and never shipped a model.
        default:
            return false;
    }
}

const char* preset_parameter_for(const uint32_t qualityMode) noexcept {
    switch (static_cast<NVSDK_NGX_PerfQuality_Value>(qualityMode)) {
        case NVSDK_NGX_PerfQuality_Value_MaxPerf:
            return NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Performance;
        case NVSDK_NGX_PerfQuality_Value_Balanced:
            return NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Balanced;
        case NVSDK_NGX_PerfQuality_Value_MaxQuality:
            return NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Quality;
        case NVSDK_NGX_PerfQuality_Value_UltraPerformance:
            return NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraPerformance;
        case NVSDK_NGX_PerfQuality_Value_DLAA:
            return NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_DLAA;
        default:
            return nullptr;
    }
}

bool valid_render_preset(const uint32_t renderPreset) noexcept {
    switch (static_cast<NVSDK_NGX_DLSS_Hint_Render_Preset>(renderPreset)) {
        case NVSDK_NGX_DLSS_Hint_Render_Preset_J:
        case NVSDK_NGX_DLSS_Hint_Render_Preset_K:
        case NVSDK_NGX_DLSS_Hint_Render_Preset_L:
        case NVSDK_NGX_DLSS_Hint_Render_Preset_M:
            return true;
        default:
            return false;
    }
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

    // DLAA is anti-aliasing at native resolution: its render size is its output size by
    // definition, not by query. Asking the optimal-settings callback for it invites a driver
    // answer this module would then have to second-guess, and a zero would fail a startup that
    // has nothing wrong with it.
    if (static_cast<NVSDK_NGX_PerfQuality_Value>(qualityMode) ==
        NVSDK_NGX_PerfQuality_Value_DLAA) {
        *renderWidth = outputWidth;
        *renderHeight = outputHeight;
        return kSuccess;
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

NVSDK_NGX_FeatureDiscoveryInfo make_discovery_info() noexcept {
    NVSDK_NGX_FeatureDiscoveryInfo dis;
    std::memset(&dis, 0, sizeof(dis));
    dis.SDKVersion = NVSDK_NGX_Version_API;
    dis.FeatureID = NVSDK_NGX_Feature_SuperSampling;
    dis.Identifier.IdentifierType = NVSDK_NGX_Application_Identifier_Type_Application_Id;
    dis.Identifier.v.ApplicationId = 0x0023;
    dis.ApplicationDataPath = L".";
    return dis;
}

int32_t copy_extension_name(const uint32_t index, char* name, const uint32_t nameCapacity,
                            uint32_t* extensionCount, const uint32_t count,
                            const VkExtensionProperties* properties) noexcept {
    if (extensionCount == nullptr) {
        return kInvalidParameter;
    }
    *extensionCount = count;
    if (name == nullptr) {
        // Pure count probe: report the total count and return success.
        return kSuccess;
    }
    if (index >= count) {
        return kSuccess;
    }
    if (nameCapacity == 0 || properties == nullptr) {
        return kInvalidParameter;
    }
    const size_t length = std::strlen(properties[index].extensionName);
    if (length + 1 > nameCapacity) {
        return kInvalidParameter;
    }
    std::memcpy(name, properties[index].extensionName, length + 1);
    return kSuccess;
}

int32_t release_feature() noexcept {
    if (g_state.feature == nullptr) {
        return kSuccess;
    }
    wait_device_idle();
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

} // namespace mc_dlss
