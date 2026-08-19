package me.snowmii.dlss.render.mrt

import com.mojang.blaze3d.textures.GpuTextureView
import org.joml.Matrix4f

/**
 * This frame's velocity-MRT write context for a scene pass: the scene-sized RG16_FLOAT velocity
 * view to write into at color index 1, and the camera motion the pass must derive vectors from.
 *
 * The stress pass is the first production writer of the velocity MRT. It gets its context from
 * the open world phase at the tail of `LevelRenderer.render`: [view] is
 * `WorldPhase.terrainVelocityView`, non-null only on an open VELOCITY_MRT route that holds a
 * scene velocity companion, and [reprojection] / [reset] come from the frame's published motion.
 *
 * [reprojection] maps a pixel's *jittered* clip position - what the rendered frame and its
 * reversed-Z depth actually hold - to where that same surface sat in the previous frame, so the
 * shader's `ndc(reprojection * clip) - ndc(clip)` is the jitter-free camera motion in normalized
 * device units. It is null when this frame publishes no motion at all (the projection seam never
 * ran), in which case every pixel is invalid.
 *
 * [reset] marks a frame with no valid predecessor: the first DLSS frame, a frame after a lost
 * or vanilla frame, or a discontinuity. Such a frame's reprojection is the identity, which would
 * otherwise read as "the camera stood still" - so a reset forces every pixel to the invalid
 * sentinel instead of the identity-derived zero.
 */
data class VelocityContext(
	val view: GpuTextureView,
	val reprojection: Matrix4f?,
	val reset: Boolean,
)
