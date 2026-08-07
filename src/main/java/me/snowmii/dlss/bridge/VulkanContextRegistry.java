package me.snowmii.dlss.bridge;

/**
 * Mod-level holder for the captured Minecraft Vulkan context. Registered once at
 * {@code com.mojang.blaze3d.vulkan.VulkanDevice} construction by
 * {@code me.snowmii.dlss.mixin.VulkanDeviceContextMixin}; read by later renderer slices inside
 * the frame. Volatile so the render thread always sees the captured context.
 *
 * <p>Java rather than Kotlin because that is all this is: one static volatile field with a
 * setter. A Kotlin {@code object} expressed the same thing as a singleton instance plus a
 * {@code @JvmStatic} accessor, purely so the Java mixin that writes it could reach it.
 */
public final class VulkanContextRegistry {
	private static volatile VulkanContext current;

	private VulkanContextRegistry() {
	}

	/** The captured context, or null before the Vulkan device has been constructed. */
	public static VulkanContext getCurrent() {
		return current;
	}

	/** Stores the captured context. A later capture (device recreate) replaces the earlier one. */
	public static void register(final VulkanContext context) {
		current = context;
	}
}
