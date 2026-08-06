package me.snowmii.dlss

/**
 * The two evaluation images the native bridge owns.
 *
 * DLSS writes its upscaled result somewhere Minecraft does not own, and reads camera motion
 * from an image Minecraft has no equivalent of, so both are allocated natively from the
 * configured dimensions: [motionImage] at render size, [outputImage] at output size. The colour
 * and depth resources stay Minecraft's - they are the scene target the world already rendered
 * into - which is why only these two cross back over the ABI.
 *
 * Handles are raw `VkImage` and `VkImageView` values, and the formats are raw `VkFormat`
 * values, in the same units [DlssNativeApi.evaluate] takes them.
 */
data class DlssEvaluationImages(
	val motionImage: Long,
	val motionView: Long,
	val motionFormat: Int,
	val outputImage: Long,
	val outputView: Long,
	val outputFormat: Int,
)
