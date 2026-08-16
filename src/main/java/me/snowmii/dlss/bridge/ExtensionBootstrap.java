package me.snowmii.dlss.bridge;
import me.snowmii.dlss.config.ModConfig;
import me.snowmii.streamline.SlQueueRequirements;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Queries NGX's exact Vulkan requirements at Minecraft's pre-creation bootstrap seams. */
public final class ExtensionBootstrap {
	/**
	 * Namespaced the same way the mod's other assets are, so in-game code can address it as
	 * {@code McDlss.id("native/mc_dlss.dll")}. These seams run inside
	 * {@code VulkanInstance.<init>}, before {@code Minecraft} exists, so there is no
	 * ResourceManager yet and the bootstrap reads it off the classloader instead.
	 */
	static final String RESOURCE_PATH = "/assets/mc-dlss/native/mc_dlss.dll";
	static final String STREAMLINE_RESOURCE_PATH = "/assets/mc-dlss/native/sl.interposer.dll";

	private static final Path RELATIVE_LIBRARY = Path.of("build", "native", "mc_dlss.dll");
	private static final Path RELATIVE_STREAMLINE = Path.of("build", "resources", "main", "assets", "mc-dlss", "native");

	private static volatile Path extracted;
	private static volatile boolean streamlineRuntimeLoaded;

	private ExtensionBootstrap() {
	}

	public static List<String> queryInstanceExtensions() {
		loadStreamlineRuntime();
		try (Native nativeBridge = Native.open(nativeLibrary())) {
			bootstrap(nativeBridge);
			return nativeBridge.queryInstanceExtensions();
		}
	}

	public static void addDeviceExtensions(
		final Collection<String> extensions,
		final long vkInstance,
		final long vkPhysicalDevice
	) {
		loadStreamlineRuntime();
		try (Native nativeBridge = Native.open(nativeLibrary())) {
			bootstrap(nativeBridge);
			extensions.addAll(nativeBridge.queryDeviceExtensions(vkInstance, vkPhysicalDevice));
		}
	}

	/**
	 * The deduplicated Vulkan 1.2 feature names Streamline's loaded features require the device
	 * to enable, merged into Minecraft's enabled feature set at the createDevice seam. Opens a
	 * fresh bridge like the other queries; bootstrap is idempotent.
	 */
	public static List<String> queryDeviceFeatures12() {
		loadStreamlineRuntime();
		try (Native nativeBridge = Native.open(nativeLibrary())) {
			bootstrap(nativeBridge);
			return nativeBridge.queryDeviceFeatures12();
		}
	}

	/**
	 * The deduplicated Vulkan 1.3 feature names Streamline's loaded features require, merged
	 * into Minecraft's enabled feature set at the createDevice seam.
	 */
	public static List<String> queryDeviceFeatures13() {
		loadStreamlineRuntime();
		try (Native nativeBridge = Native.open(nativeLibrary())) {
			bootstrap(nativeBridge);
			return nativeBridge.queryDeviceFeatures13();
		}
	}

	/**
	 * The summed extra graphics / compute / optical-flow queues Streamline's loaded features
	 * require the host to create, added to Minecraft's queue-family create map at the
	 * createDevice seam. Optical flow is reported but never created: without a host optical-flow
	 * family, DLSS-G runs in interop mode.
	 */
	public static SlQueueRequirements queryQueueRequirements() {
		loadStreamlineRuntime();
		try (Native nativeBridge = Native.open(nativeLibrary())) {
			bootstrap(nativeBridge);
			return nativeBridge.queryQueueRequirements();
		}
	}

	/**
	 * Activates Streamline's manual-hook Vulkan proxies against the live device, right after
	 * the VulkanDevice is constructed. Opens a fresh bridge like the other seams (bootstrap is
	 * idempotent and the Streamline state survives the bridge close) and throws
	 * {@link NativeException} if slSetVulkanInfo fails, so a device that Streamline cannot hook
	 * fails loudly at the same seam where bootstrap already throws.
	 */
	public static void activateVulkanProxies(final VulkanContext context) {
		Objects.requireNonNull(context, "context");
		loadStreamlineRuntime();
		try (Native nativeBridge = Native.open(nativeLibrary())) {
			bootstrap(nativeBridge);
			final int result = nativeBridge.activateVulkanProxies(
				context.getInstanceHandle(),
				context.getPhysicalDeviceHandle(),
				context.getDeviceHandle(),
				context.getGraphicsQueueFamily(),
				context.getGraphicsQueueIndex(),
				context.getComputeQueueFamily(),
				context.getComputeQueueIndex()
			);
			if (result != NativeApi.SUCCESS_RESULT) {
				throw new NativeException("activate-vulkan-proxies", result);
			}
		}
	}

	private static void loadStreamlineRuntime() {
		if (streamlineRuntimeLoaded) return;
		synchronized (ExtensionBootstrap.class) {
			if (streamlineRuntimeLoaded) return;
			final Path runtime = streamlineRuntimeDirectory();
			for (String name : List.of("sl.common.dll", "sl.interposer.dll")) {
				System.load(runtime.resolve(name).toAbsolutePath().toString());
			}
			streamlineRuntimeLoaded = true;
		}
	}

	private static void bootstrap(final Native nativeBridge) {
		final int result = nativeBridge.bootstrapStreamline(streamlineRuntimeDirectory());
		if (result != NativeApi.SUCCESS_RESULT) {
			throw new NativeException("bootstrap-streamline", result);
		}
	}

	/**
	 * The staged Streamline runtime directory: where sl.common.dll, sl.interposer.dll, and the
	 * feature plugins were copied by processResources. Resolves the packaged resource first,
	 * then walks up from the working directory for plain {@code buildNativeDlss} runs.
	 */
	public static Path streamlineRuntimeDirectory() {
		final URL resource = ExtensionBootstrap.class.getResource(STREAMLINE_RESOURCE_PATH);
		if (resource != null && "file".equals(resource.getProtocol())) {
			try {
				return Path.of(resource.toURI()).getParent();
			} catch (URISyntaxException error) {
				throw new NativeException("bootstrap-streamline", error);
			}
		}
		for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
			final Path candidate = directory.resolve(RELATIVE_STREAMLINE);
			if (Files.isRegularFile(candidate.resolve("sl.interposer.dll"))) return candidate;
		}
		throw new NativeException("bootstrap-streamline", new IllegalStateException("Staged Streamline runtime not found; run ./gradlew.bat processResources"));
	}

	/**
	 * Locates the workstation-local native bridge: explicit override first, then the packaged
	 * namespaced resource, then the build output relative to an ancestor of the working
	 * directory. The last case keeps a plain {@code buildNativeDlss} run usable before
	 * resources are processed; the dev client's working directory is {@code run/} while tests
	 * run from the repository root, so the walk-up covers both.
	 */
	public static Path nativeLibrary() {
		final Path configured = ModConfig.fromSystemProperties().getNativeLibraryPath();
		if (configured != null) {
			return configured.toAbsolutePath();
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

		throw new NativeException(
			"load-library",
			new IllegalStateException(
				"Native DLSS bridge not found. Run ./gradlew.bat buildNativeDlss, or set -D"
					+ ModConfig.NATIVE_LIBRARY_PROPERTY + "=<path to mc_dlss.dll>. Tried:" + tried
			)
		);
	}

	/**
	 * Resolves the packaged resource to a real filesystem path, because
	 * {@link java.lang.foreign.SymbolLookup#libraryLookup} cannot load from inside a jar.
	 * A loose file on the classpath is loaded in place; a jar entry is extracted once.
	 */
	private static Path packagedLibrary() {
		final URL resource = ExtensionBootstrap.class.getResource(RESOURCE_PATH);
		if (resource == null) {
			return null;
		}

		if ("file".equals(resource.getProtocol())) {
			try {
				return Path.of(resource.toURI());
			} catch (URISyntaxException error) {
				throw new NativeException("load-library", error);
			}
		}

		final Path cached = extracted;
		if (cached != null && Files.isRegularFile(cached)) {
			return cached;
		}

		synchronized (ExtensionBootstrap.class) {
			if (extracted != null && Files.isRegularFile(extracted)) {
				return extracted;
			}
			try (InputStream source = ExtensionBootstrap.class.getResourceAsStream(RESOURCE_PATH)) {
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
				throw new NativeException("load-library", error);
			}
		}
	}
}
