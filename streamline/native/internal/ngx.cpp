#include "internal/ngx.h"

#include <nvsdk_ngx.h>

/*
 * The retired direct-NGX implementation used to live here: feature discovery, the
 * optimal-settings callback, capability parameters, and feature release. All of it is gone;
 * the unit now holds only the quality-mode and render-preset vocabulary the ABI still speaks
 * in, and the Streamline unit maps those values onto sl:: types.
 */
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

} // namespace mc_dlss
