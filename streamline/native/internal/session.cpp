#include "internal/session.h"

#include "internal/images.h"
#include "internal/motion.h"
#include "internal/sl_dlss.h"
#include "internal/state.h"
#include "internal/timing.h"

namespace mc_dlss {

int32_t shutdown_state() noexcept {
    g_state.sessionReady = false;
    // Diagnostic and device-owned, so it goes before anything that could fail and leave the
    // shutdown half-done.
    destroy_timing();
    // Pipeline, descriptors, and sampler belong to the device Minecraft is about to destroy,
    // and none of them is known to Streamline, so they go first and independently of it.
    destroy_motion_pass();
    // Images belong to the device Minecraft is about to destroy, so they die first.
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

} // namespace mc_dlss
