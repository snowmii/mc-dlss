#ifndef MC_DLSS_INTERNAL_MOTION_H
#define MC_DLSS_INTERNAL_MOTION_H

#include "internal/state.h"

#include <cstdint>

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

// The fixed workgroup size of both motion dispatches, duplicated from the shaders
// (local_size_x/y = 8 in mc_dlss_motion.comp and mc_dlss_velocity_fill.comp) because the
// dispatch has to round the render size up to whole workgroups; the shaders' own bounds
// checks are what keep the surplus invocations harmless.
constexpr uint32_t kMotionWorkgroupSize = 8;

// Rounds one render-size dimension up to whole kMotionWorkgroupSize workgroups, the group
// count record_motion and record_velocity_fill give vkCmdDispatch for that axis. Pure
// unsigned arithmetic - no device in sight - so the doctest harness pins it. The wrap
// ceiling is pinned, not silently fixed: an extent within kMotionWorkgroupSize - 1 of
// UINT32_MAX wraps and dead-reckons to 0 groups, and no render extent can get there.
inline uint32_t motion_workgroup_count(uint32_t extent) noexcept {
    return (extent + kMotionWorkgroupSize - 1) / kMotionWorkgroupSize;
}

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
// is handed back in the layout it arrived in; the motion image is left in GENERAL. The same
// dispatch also fills the flipped motion copy the FG tag names - every vector mirrored
// vertically with its y component negated - so DLSS-G's backbuffer-oriented inputs and SR's
// engine-oriented ones stay one dispatch apart.
//
// Records and never submits: the work is ordered by Minecraft's own graphics submission.
int32_t record_motion(const McDlssMotionInfo& info) noexcept;

// Records the dispatch that merges `info.depth` and `info.velocity` into the module's motion
// image, plus the transitions and the two barriers that order the read of the scene's
// velocity writes and the visibility of the merge's writes to the tag and evaluation. The
// depth image is handed back in the layout it arrived in; the velocity companion stays in
// GENERAL; the motion image is left in GENERAL. The same dispatch fills the flipped motion
// copy the FG tag names with the mirrored, y-negated field, exactly like the camera-only
// pass.
//
// Records and never submits: the work is ordered by Minecraft's own graphics submission.
int32_t record_velocity_fill(const McDlssFillVelocityInfo& info) noexcept;

// Centre-pixel motion/depth from the probe ring slot two records old, or kNotInitialized until
// three motion dispatches have completed. Never waits on the GPU.
int32_t query_motion_probe(float* motionX, float* motionY, float* depth, int32_t* slot) noexcept;

} // namespace mc_dlss

#endif
