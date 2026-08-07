#ifndef MC_DLSS_INTERNAL_MOTION_H
#define MC_DLSS_INTERNAL_MOTION_H

#include "internal/state.h"

/*
 * The camera-only motion-vector compute pass.
 *
 * DLSS reads motion from an image nothing in Minecraft fills, so this pass fills it: one
 * dispatch reads the engine's depth image, maps every pixel's clip position through the
 * caller's reprojection matrix, and stores the normalized-device difference into the motion
 * image the images unit owns.
 *
 * Deliberately free of frame timing: the stage boundaries are stamped by the caller, so the
 * whole recorded sequence for a frame reads in one place rather than being spread across the
 * units that make it up.
 */
namespace mc_dlss {

// Builds the whole pass, or leaves nothing behind. Reused for the life of the session -
// nothing here is per frame.
int32_t create_motion_pass() noexcept;

// Destroys in the reverse of creation order. Every handle is checked because this also runs
// as the cleanup path of a partially created pass.
void destroy_motion_pass() noexcept;

// Records the dispatch that fills the motion image from `info.depth`, plus the transitions
// around it and the barrier that makes its writes visible to the evaluation. The depth image
// is handed back in the layout it arrived in; the motion image is left in GENERAL.
//
// Records and never submits: the work is ordered by Minecraft's own graphics submission.
int32_t record_motion(const McDlssMotionInfo& info) noexcept;

} // namespace mc_dlss

#endif
