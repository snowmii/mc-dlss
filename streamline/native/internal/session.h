#ifndef MC_DLSS_INTERNAL_SESSION_H
#define MC_DLSS_INTERNAL_SESSION_H

#include "internal/common.h"

/*
 * Teardown ordering.
 *
 * Every unit below this one owns some GPU object, and the order they are destroyed in is a
 * real constraint rather than a preference: the motion pass before the device that owns it,
 * the images before the device that owns them. That ordering is the only thing this unit
 * knows, which is why it sits above all of them - putting it in the state unit would make
 * state depend on its own dependents.
 *
 * Close and reset release module-owned Vulkan resources and retained Streamline frame
 * state; close also shuts the process-wide Streamline runtime down - after those resources,
 * while the caller's device is still alive - and reset_state forgets bootstrap/proxy
 * bookkeeping so a later bootstrap re-runs slInit.
 */
namespace mc_dlss {

int32_t shutdown_state() noexcept;

} // namespace mc_dlss

#endif
