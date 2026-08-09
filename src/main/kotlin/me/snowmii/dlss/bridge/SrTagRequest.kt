package me.snowmii.dlss.bridge

/**
 * One frame's DLSS SR resources, tagged on the caller's command buffer in the units the flat
 * native ABI takes them.
 *
 * [commandBuffer] is the caller's shared Vulkan command buffer the tags are recorded for - the
 * same buffer the frame's motion pass and evaluation are recorded on. [color] and [depth] are
 * the engine's render-sized colour and depth images.
 *
 * [velocity] is the engine's render-sized RG16_FLOAT velocity companion, tagged as the
 * motion-vector buffer on the velocity-MRT route. An all-zero [velocity] means the route
 * carries no velocity companion, and the native side tags the module's own motion image
 * instead - the camera-only path the compute writer fills.
 *
 * The bridge's own motion and output images are never carried here: they are tagged from
 * native state when they have been acquired for the configured dimensions, and skipped until
 * then.
 */
data class SrTagRequest(
	val commandBuffer: Long = 0,
	val color: ImageBinding = ImageBinding(0, 0, 0),
	val depth: ImageBinding = ImageBinding(0, 0, 0),
	val velocity: ImageBinding = ImageBinding(0, 0, 0),
)
