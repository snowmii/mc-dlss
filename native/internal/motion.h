#ifndef MC_DLSS_INTERNAL_MOTION_H
#define MC_DLSS_INTERNAL_MOTION_H

#include "internal/state.h"

/*
 * The two compute passes this module records: the camera-only motion pass and the
 * velocity-MRT merge fill.
 *
 * The camera-only pass fills the module's own motion image: one dispatch reads the engine's
 * depth image, maps every pixel's clip position through the caller's reprojection matrix,
 * and stores the normalized-device difference into the motion image the images unit owns.
 *
 * The velocity fill writes the same motion image as the camera-only pass, but from two
 * sampled inputs: it reads the engine's depth image and its sparse RG16_FLOAT velocity
 * companion, copies every non-sentinel object vector unchanged, reconstructs the same
 * jitter-stripped camera reprojection for every sentinel pixel, and writes the invalid
 * sentinel everywhere on a reset frame. The companion is a sampled input only and is never
 * bound as storage; it stays in GENERAL, the layout the engine rests it in.
 *
 * Both passes are deliberately free of frame timing: the stage boundaries are stamped by the
 * caller, so the whole recorded sequence for a frame reads in one place rather than being
 * spread across the units that make it up.
 */
namespace mc_dlss {

// Builds the camera-only motion pass, or leaves nothing behind. Reused for the life of the
// session - nothing here is per frame.
int32_t create_motion_pass() noexcept;

// Builds the velocity-fill pass, or leaves nothing behind. Reused for the life of the
// session - nothing here is per frame.
int32_t create_velocity_fill_pass() noexcept;

// Destroys both passes in the reverse of creation order. Every handle is checked because the
// underlying destroy also runs as the cleanup path of a partially created pass.
void destroy_motion_pass() noexcept;

// Records the dispatch that fills the motion image from `info.depth`, plus the transitions
// around it and the barrier that makes its writes visible to the evaluation. The depth image
// is handed back in the layout it arrived in; the motion image is left in GENERAL.
//
// Records and never submits: the work is ordered by Minecraft's own graphics submission.
int32_t record_motion(const McDlssMotionInfo& info) noexcept;

// Records the dispatch that merges `info.depth` and `info.velocity` into the module's motion
// image, plus the transitions and the two barriers that order the read of the scene's
// velocity writes and the visibility of the merge's writes to the tag and evaluation. The
// depth image is handed back in the layout it arrived in; the velocity companion stays in
// GENERAL; the motion image is left in GENERAL.
//
// Records and never submits: the work is ordered by Minecraft's own graphics submission.
int32_t record_velocity_fill(const McDlssFillVelocityInfo& info) noexcept;

} // namespace mc_dlss

#endif
