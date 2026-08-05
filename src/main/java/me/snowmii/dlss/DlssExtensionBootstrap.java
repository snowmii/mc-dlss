package me.snowmii.dlss;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/** Queries NGX's exact Vulkan requirements at Minecraft's pre-creation bootstrap seams. */
public final class DlssExtensionBootstrap {
	private static final Path NATIVE_LIBRARY = Path.of("build", "native", "mc_dlss.dll").toAbsolutePath();

	private DlssExtensionBootstrap() {
	}

	public static List<String> queryInstanceExtensions() {
		try (DlssNative nativeBridge = DlssNative.open(NATIVE_LIBRARY)) {
			return nativeBridge.queryInstanceExtensions();
		}
	}

	public static void addDeviceExtensions(
		final Collection<String> extensions,
		final long vkInstance,
		final long vkPhysicalDevice
	) {
		try (DlssNative nativeBridge = DlssNative.open(NATIVE_LIBRARY)) {
			extensions.addAll(nativeBridge.queryDeviceExtensions(vkInstance, vkPhysicalDevice));
		}
	}
}
