package me.snowmii.dlss;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.List;

/** Queries NGX's exact Vulkan requirements at Minecraft's pre-creation bootstrap seams. */
public final class DlssExtensionBootstrap {
	/**
	 * Namespaced the same way the mod's other assets are, so in-game code can address it as
	 * {@code McDlss.id("native/mc_dlss.dll")}. These seams run inside
	 * {@code VulkanInstance.<init>}, before {@code Minecraft} exists, so there is no
	 * ResourceManager yet and the bootstrap reads it off the classloader instead.
	 */
	static final String RESOURCE_PATH = "/assets/mc-dlss/native/mc_dlss.dll";

	private static final Path RELATIVE_LIBRARY = Path.of("build", "native", "mc_dlss.dll");

	private static volatile Path extracted;

	private DlssExtensionBootstrap() {
	}

	public static List<String> queryInstanceExtensions() {
		try (DlssNative nativeBridge = DlssNative.open(nativeLibrary())) {
			return nativeBridge.queryInstanceExtensions();
		}
	}

	public static void addDeviceExtensions(
		final Collection<String> extensions,
		final long vkInstance,
		final long vkPhysicalDevice
	) {
		try (DlssNative nativeBridge = DlssNative.open(nativeLibrary())) {
			extensions.addAll(nativeBridge.queryDeviceExtensions(vkInstance, vkPhysicalDevice));
		}
	}

	/**
	 * Locates the workstation-local native bridge: explicit override first, then the packaged
	 * namespaced resource, then the build output relative to an ancestor of the working
	 * directory. The last case keeps a plain {@code buildNativeDlss} run usable before
	 * resources are processed; the dev client's working directory is {@code run/} while tests
	 * run from the repository root, so the walk-up covers both.
	 */
	static Path nativeLibrary() {
		final String configured = System.getProperty(DlssStartupConfig.NATIVE_LIBRARY_PROPERTY);
		if (configured != null && !configured.isBlank()) {
			return Path.of(configured.trim()).toAbsolutePath();
		}

		final Path packaged = packagedLibrary();
		if (packaged != null) {
			return packaged;
		}

		final StringBuilder tried = new StringBuilder("\n  classpath:").append(RESOURCE_PATH);
		for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
			final Path candidate = directory.resolve(RELATIVE_LIBRARY);
			if (Files.isRegularFile(candidate)) {
				return candidate;
			}
			tried.append("\n  ").append(candidate);
		}

		throw new DlssNativeException(
			"load-library",
			new IllegalStateException(
				"Native DLSS bridge not found. Run ./gradlew.bat buildNativeDlss, or set -D"
					+ DlssStartupConfig.NATIVE_LIBRARY_PROPERTY + "=<path to mc_dlss.dll>. Tried:" + tried
			)
		);
	}

	/**
	 * Resolves the packaged resource to a real filesystem path, because
	 * {@link java.lang.foreign.SymbolLookup#libraryLookup} cannot load from inside a jar.
	 * A loose file on the classpath is loaded in place; a jar entry is extracted once.
	 */
	private static Path packagedLibrary() {
		final URL resource = DlssExtensionBootstrap.class.getResource(RESOURCE_PATH);
		if (resource == null) {
			return null;
		}

		if ("file".equals(resource.getProtocol())) {
			try {
				return Path.of(resource.toURI());
			} catch (URISyntaxException error) {
				throw new DlssNativeException("load-library", error);
			}
		}

		final Path cached = extracted;
		if (cached != null && Files.isRegularFile(cached)) {
			return cached;
		}

		synchronized (DlssExtensionBootstrap.class) {
			if (extracted != null && Files.isRegularFile(extracted)) {
				return extracted;
			}
			try (InputStream source = DlssExtensionBootstrap.class.getResourceAsStream(RESOURCE_PATH)) {
				if (source == null) {
					return null;
				}
				final Path directory = Files.createTempDirectory("mc-dlss-native");
				directory.toFile().deleteOnExit();
				final Path target = directory.resolve("mc_dlss.dll");
				Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
				target.toFile().deleteOnExit();
				extracted = target;
				return target;
			} catch (IOException error) {
				throw new DlssNativeException("load-library", error);
			}
		}
	}
}
