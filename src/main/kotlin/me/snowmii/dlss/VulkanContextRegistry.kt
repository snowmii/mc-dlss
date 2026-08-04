package me.snowmii.dlss

/**
 * Mod-level holder for the captured Minecraft Vulkan context. Registered once at
 * [com.mojang.blaze3d.vulkan.VulkanDevice] construction by
 * [me.snowmii.mixin.VulkanDeviceContextMixin]; read by later renderer slices inside
 * the frame. Volatile so the render thread always sees the captured context.
 */
object VulkanContextRegistry {
	@Volatile
	var current: DlssVulkanContext? = null
		private set

	/** Stores the captured context. A later capture (device recreate) replaces the earlier one. */
	@JvmStatic
	fun register(context: DlssVulkanContext) {
		current = context
	}
}
