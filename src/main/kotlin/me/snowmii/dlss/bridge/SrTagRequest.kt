package me.snowmii.dlss.bridge

/**
 * One frame's DLSS SR resources, tagged on the caller's command buffer in the units the flat
 * native ABI takes them.
 *
 * [commandBuffer] is the caller's shared Vulkan command buffer the tags are recorded for - the
 * same buffer the frame's motion pass and evaluation are recorded on. [color] and [depth] are
 * the engine's render-sized colour and depth images.
 *
 * The motion source is never carried here: it is always the bridge's own motion image, tagged
 * from native state once it has been acquired for the configured dimensions - the camera-only
 * route fills it with [MotionRequest], the velocity-MRT route merges the scene companion into
 * it with [FillVelocityRequest]. Direct companion tagging is retired.
 */
data class SrTagRequest(
	val commandBuffer: Long = 0,
	val color: ImageBinding = ImageBinding(0, 0, 0),
	val depth: ImageBinding = ImageBinding(0, 0, 0),
)
