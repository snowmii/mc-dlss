package me.snowmii.streamline;

/**
 * Mod-level holder for the captured Minecraft Vulkan context. Registered once at
 * {@code com.mojang.blaze3d.vulkan.VulkanDevice} construction by
 * {@code me.snowmii.dlss.mixin.VulkanDeviceContextMixin}; read by later renderer code inside
 * the frame. Volatile so the render thread always sees the captured context.
 */
public final class VulkanContextRegistry {
	private static volatile VulkanContext currentContext;

	private VulkanContextRegistry() {
	}

	public static VulkanContext getCurrent() {
		return currentContext;
	}

	public static void register(final VulkanContext context) {
		currentContext = context;
	}
}
