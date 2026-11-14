#include "internal/ngx.h"

#include <nvsdk_ngx.h>

namespace mc_dlss {

bool valid_quality_mode(const uint32_t qualityMode) noexcept {
    switch (static_cast<NVSDK_NGX_PerfQuality_Value>(qualityMode)) {
        case NVSDK_NGX_PerfQuality_Value_MaxPerf:
        case NVSDK_NGX_PerfQuality_Value_Balanced:
        case NVSDK_NGX_PerfQuality_Value_MaxQuality:
        case NVSDK_NGX_PerfQuality_Value_UltraPerformance:
        case NVSDK_NGX_PerfQuality_Value_DLAA:
            return true;
        default:
            return false;
    }
}

bool valid_render_preset(const uint32_t renderPreset) noexcept {
    switch (static_cast<NVSDK_NGX_DLSS_Hint_Render_Preset>(renderPreset)) {
        case NVSDK_NGX_DLSS_Hint_Render_Preset_Default:
        case NVSDK_NGX_DLSS_Hint_Render_Preset_J:
        case NVSDK_NGX_DLSS_Hint_Render_Preset_K:
        case NVSDK_NGX_DLSS_Hint_Render_Preset_L:
        case NVSDK_NGX_DLSS_Hint_Render_Preset_M:
            return true;
        default:
            return false;
    }
}

bool valid_rr_preset(const uint32_t rrPreset) noexcept {
    switch (rrPreset) {
        case 0:
        case 4:
        case 5:
        case 6:
            return true;
        default:
            return false;
    }
}

}
