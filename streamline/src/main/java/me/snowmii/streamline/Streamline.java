package me.snowmii.streamline;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/** Java entry point for Streamline configuration, requirements, and sessions. */
public final class Streamline {
	private Streamline() {}

	public static void configure(final Path nativeLibrary) {
		ExtensionBootstrap.setNativeLibraryPath(nativeLibrary);
	}

	public static StreamlineSession open() {
		return ExtensionBootstrap.openSession();
	}

	public static List<String> queryInstanceExtensions() {
		return ExtensionBootstrap.queryInstanceExtensions();
	}

	public static void addDeviceExtensions(
		final Collection<String> extensions,
		final long vkInstance,
		final long vkPhysicalDevice
	) {
		ExtensionBootstrap.addDeviceExtensions(extensions, vkInstance, vkPhysicalDevice);
	}

	public static List<String> queryDeviceFeatures12() {
		return ExtensionBootstrap.queryDeviceFeatures12();
	}

	public static List<String> queryDeviceFeatures13() {
		return ExtensionBootstrap.queryDeviceFeatures13();
	}

	public static SlQueueRequirements queryQueueRequirements() {
		return ExtensionBootstrap.queryQueueRequirements();
	}

	public static void activateVulkanProxies(final VulkanContext context) {
		ExtensionBootstrap.activateVulkanProxies(context);
	}
}
