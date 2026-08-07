#ifndef MC_DLSS_INTERNAL_SESSION_H
#define MC_DLSS_INTERNAL_SESSION_H

#include "internal/common.h"

/*
 * Teardown ordering.
 *
 * Every unit below this one owns some GPU or NGX object, and the order they are destroyed in
 * is a real constraint rather than a preference: the feature must die before its parameters,
 * the images before the device, the pipeline before the device that owns it. That ordering is
 * the only thing this unit knows, which is why it sits above all of them - putting it in the
 * state unit would make state depend on its own dependents.
 */
namespace mc_dlss {

int32_t shutdown_state() noexcept;

int32_t cleanup_after_initialize_failure(int32_t primaryFailure) noexcept;

} // namespace mc_dlss

#endif
