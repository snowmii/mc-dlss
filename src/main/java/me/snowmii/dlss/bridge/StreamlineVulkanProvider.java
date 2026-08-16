package me.snowmii.dlss.bridge;

import java.nio.file.Files;
import java.nio.file.Path;
import me.snowmii.streamline.NativeException;

/**
 * Redirects LWJGL's Vulkan loading so Minecraft's Vulkan calls route through Streamline's
 * staged interposer instead of the driver loader.
 *
 * <p>sl.interposer.dll exports the full Vulkan surface (vkCreateInstance, vkGetDeviceProcAddr,
 * vkQueuePresentKHR, vkAcquireNextImageKHR, vkCreateSwapchainKHR, ...), and LWJGL loads the
 * Vulkan library through {@code Library.loadNative(VK.class, "org.lwjgl.vulkan", ...)}, which
 * the {@code org.lwjgl.vulkan.libname} system property overrides - an absolute path is loaded
 * directly. This is the documented slvk-style integration: dynamically load sl.interposer.dll
 * instead of vulkan-1.dll.
 *
 * <p>Must run before any {@code org.lwjgl.vulkan} class is touched. Minecraft's first touch is
 * {@code VulkanInstance.<init>}, and this seam is invoked from the mod entrypoint's
 * {@code onInitialize}, which Fabric runs well before that. The redirect is unconditional,
 * mirroring the unconditional {@code slInit} at the instance seam.
 */
public final class StreamlineVulkanProvider {
	/** LWJGL system property overriding the Vulkan library name; an absolute path is loaded as-is. */
	static final String LWJGL_VULKAN_LIBNAME = "org.lwjgl.vulkan.libname";
	private static final String INTERPOSER_NAME = "sl.interposer.dll";

	private StreamlineVulkanProvider() {
	}

	/**
	 * Points {@code org.lwjgl.vulkan.libname} at the staged interposer and returns its absolute
	 * path. Throws {@link NativeException} when the staged runtime is missing, so the caller can
	 * decide how loudly to fail.
	 */
	public static Path redirectToInterposer() {
		final Path interposer = ExtensionBootstrap.streamlineRuntimeDirectory().resolve(INTERPOSER_NAME);
		if (!Files.isRegularFile(interposer)) {
			throw new NativeException(
				"vulkan-redirect",
				new IllegalStateException("Staged Streamline interposer not found: " + interposer)
			);
		}
		final Path absolute = interposer.toAbsolutePath();
		System.setProperty(LWJGL_VULKAN_LIBNAME, absolute.toString());
		return absolute;
	}
}
