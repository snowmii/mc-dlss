package me.snowmii.dlss.vk;

/**
 * Holds the captured Minecraft Vulkan context. Registered once at {@code VulkanDevice}
 * construction; read by consumers on the render thread.
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
