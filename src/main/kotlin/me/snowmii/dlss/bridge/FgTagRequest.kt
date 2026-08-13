package me.snowmii.dlss.bridge

/**
 * One frame's DLSS-G resources, tagged on the caller's command buffer in the units the flat
 * native ABI takes them.
 *
 * [commandBuffer] is the caller's shared Vulkan command buffer the tags are recorded for.
 * [depth] is the engine's render-sized depth image, which must be D32_SFLOAT; [hudless] is
 * the engine's output-sized HUD-less colour and [ui] its output-sized UI colour+alpha
 * target, both of which must be R8G8B8A8_UNORM - the same formats the native side's FG
 * options record, and anything else is refused.
 *
 * The motion source is never carried here: it is always the bridge's own motion image,
 * tagged from native state. The native side refuses the call until the DLSS-G options
 * recorded for the stored configuration and the motion image was acquired for the
 * configured dimensions - the camera-only route fills it with [MotionRequest], the
 * velocity-MRT route merges the scene companion into it with [FillVelocityRequest]. Direct
 * companion tagging is retired. The backbuffer/output chain is present interception rather
 * than a tag, so no output image is carried either.
 */
data class FgTagRequest(
	val commandBuffer: Long = 0,
	val depth: ImageBinding = ImageBinding(0, 0, 0),
	val hudless: ImageBinding = ImageBinding(0, 0, 0),
	val ui: ImageBinding = ImageBinding(0, 0, 0),
)
