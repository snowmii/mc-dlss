#include "internal/session.h"

#include "internal/images.h"
#include "internal/motion.h"
#include "internal/ngx.h"
#include "internal/state.h"
#include "internal/timing.h"

#include <nvsdk_ngx_vk.h>

namespace mc_dlss {

int32_t shutdown_state() noexcept {
    g_state.bootstrapComplete = false;
    // Diagnostic and device-owned, so it goes before anything that could fail and leave the
    // shutdown half-done.
    destroy_timing();
    // Pipeline, descriptors, and sampler belong to the device Minecraft is about to destroy,
    // and none of them is known to NGX, so they go first and independently of it.
    destroy_motion_pass();
    // Images belong to the device NGX is about to release and Minecraft is about
    // to destroy, so they die first.
    release_images();
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

} // namespace mc_dlss
