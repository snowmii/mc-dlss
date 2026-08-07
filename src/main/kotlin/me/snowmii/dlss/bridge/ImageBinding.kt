package me.snowmii.dlss.bridge

/**
 * One engine image handed to the native ABI: its raw handles and format.
 *
 * The subresource range is deliberately absent. Every image Minecraft's Vulkan backend creates
 * is a single-level, single-layer 2D image, and the native side derives the range - and the
 * aspect, from the binding's role - rather than having it carried. The ABI used to carry all
 * five range fields per image and every producer sent the same values.
 */
data class ImageBinding(
	val view: Long,
	val image: Long,
	val format: Int,
)
