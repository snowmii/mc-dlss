#ifndef MC_DLSS_INTERNAL_NGX_H
#define MC_DLSS_INTERNAL_NGX_H

#include "internal/state.h"

namespace mc_dlss {

bool valid_quality_mode(uint32_t qualityMode) noexcept;

bool valid_render_preset(uint32_t renderPreset) noexcept;

bool valid_rr_preset(uint32_t rrPreset) noexcept;

}

#endif
