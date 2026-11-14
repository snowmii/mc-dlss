#include "internal/session.h"

#include "internal/images.h"
#include "internal/motion.h"
#include "internal/sl_dlss.h"
#include "internal/state.h"
#include "internal/tonemap.h"
#include "internal/timing.h"

namespace mc_dlss {

int32_t shutdown_state() noexcept {
    g_state.sessionReady = false;
    destroy_timing();
    destroy_tone_map_pass();
    destroy_motion_pass();
    release_images();
    // Streamline's plugins keep their worker threads (CUDA/NGX) running until slShutdown, and
    // those threads reach into the live Vulkan device: shutting the runtime down here - after
    // the module's own resources are gone and while the caller's device is still alive - is
    // what keeps process exit from crashing in sl.common.dll or nvcuda64.dll. reset_state then
    // drops the bootstrap, proxy, and session bookkeeping with the rest of the struct, so a
    // later bootstrap re-runs slInit and a fresh device can be activated.
    shutdown_streamline();
    reset_state();
    return kSuccess;
}

}
