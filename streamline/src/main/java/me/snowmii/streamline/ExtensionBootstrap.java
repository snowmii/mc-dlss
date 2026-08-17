package me.snowmii.streamline;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Queries Streamline's exact Vulkan requirements at the game's pre-creation bootstrap seams. */
public final class ExtensionBootstrap {
	/**
	 * Namespaced under the SDK's own asset namespace, so it resolves as the classloader resource
	 * {@code /assets/streamline-api/native/mc_dlss.dll}. These seams run before any game-side
	 * asset manager exists, so the SDK reads it off the classloader instead.
	 */
	private static final String RESOURCE_DIRECTORY = "/assets/streamline-api/native/";
	static final String RESOURCE_PATH = RESOURCE_DIRECTORY + "mc_dlss.dll";
	static final String STREAMLINE_RESOURCE_PATH = RESOURCE_DIRECTORY + "sl.interposer.dll";

	/**
	 * The colocated flat runtime set: the bridge plus every Streamline/NGX runtime dll that must
	 * sit beside it on disk for Windows dependency resolution. Mirrors the nine files staged by
	 * {@code :streamline}'s processResources into {@code assets/streamline-api/native/} (and,
	 * for parity only, under {@code streamline/}).
	 */
	private static final List<String> FLAT_RUNTIME_FILES = List.of(
		"sl.interposer.dll", "sl.common.dll", "sl.dlss.dll", "sl.dlss_g.dll", "sl.reflex.dll",
		"sl.pcl.dll",
		"nvngx_dlss.dll", "nvngx_dlssg.dll", "NvLowLatencyVk.dll"
	);

	private static final Path RELATIVE_STREAMLINE = Path.of("build", "resources", "main", "assets", "streamline-api", "native");

	/**
	 * The knob the mod's config reads for the native-library path, kept so the error message
	 * names the knob users set. Reading it is the mod's job: the mod parses it in its config and
	 * injects the result through {@link #setNativeLibraryPath}, because this seam runs before
	 * the mod's own config handle can be built.
	 */
	private static final String NATIVE_LIBRARY_PROPERTY = "mc.dlss.native-library";

	private static volatile Path configuredNativeLibrary;
	/** The temp directory holding the extracted colocated flat runtime once extracted, else null. */
	private static volatile Path extracted;
	private static volatile boolean streamlineRuntimeLoaded;

	private ExtensionBootstrap() {
	}

	/** Opens one Java session over the packaged native implementation. */
	public static StreamlineSession openSession() {
		loadStreamlineRuntime();
		return Native.open(nativeLibrary());
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
	 * to enable, merged into the game's enabled feature set at the createDevice seam. Opens a
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
	 * into the game's enabled feature set at the createDevice seam.
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
	 * require the host to create, added to the game's queue-family create map at the
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
			if (result != StreamlineSession.SUCCESS_RESULT) {
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
		if (result != StreamlineSession.SUCCESS_RESULT) {
			throw new NativeException("bootstrap-streamline", result);
		}
	}

	/**
	 * The staged Streamline runtime directory: where sl.common.dll, sl.interposer.dll, and the
	 * feature plugins were copied by processResources. A loose file on the classpath is returned
	 * in place; a packaged resource (plain jar or nested jar:jar: URL, which the JVM serves
	 * transparently) is extracted once, as part of the whole colocated flat directory. Walks up
	 * from the working directory for plain {@code :streamline} runs with no staged resource.
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
		if (resource != null) {
			try {
				return extractedDirectory();
			} catch (IOException error) {
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
	 * Injection seam for the workstation-local native bridge: the mod set the path once, at
	 * {@code onInitialize}, strictly before any query seam runs, from its own config. Passing
	 * null resets to packaged-resource resolution.
	 */
	public static void setNativeLibraryPath(final Path path) {
		configuredNativeLibrary = path;
	}

	/**
	 * Locates the workstation-local native bridge: the injected path first (absolute), then the
	 * packaged namespaced resource. The mod reads its {@code -Dmc.dlss.native-library} knob in
	 * its config and injects the result, so resolution does not depend on the working directory:
	 * the dev client's working directory is {@code run/} while tests run from the repository
	 * root. A bridge that exists only as build output has to be a staged resource first; there
	 * is no build-directory walk-up to fall back on.
	 */
	public static Path nativeLibrary() {
		final Path configured = configuredNativeLibrary;
		if (configured != null) {
			return configured.toAbsolutePath();
		}

		final Path packaged = packagedLibrary();
		if (packaged != null) {
			return packaged;
		}

		throw new NativeException(
			"load-library",
			new IllegalStateException(
				"Native DLSS bridge not found. Run ./gradlew.bat :streamline:buildNativeDlss, or set -D"
					+ NATIVE_LIBRARY_PROPERTY + "=<path to mc_dlss.dll>. Tried:\n  classpath:" + RESOURCE_PATH
			)
		);
	}

	/**
	 * Resolves the packaged resource to a real filesystem path, because
	 * {@link java.lang.foreign.SymbolLookup#libraryLookup(Path, Arena)} cannot load from inside a jar.
	 * A loose file on the classpath is loaded in place; a packaged resource is extracted, once,
	 * as part of the whole colocated flat directory.
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

		try {
			return extractedDirectory().resolve("mc_dlss.dll");
		} catch (IOException error) {
			throw new NativeException("load-library", error);
		}
	}

	/**
	 * Materializes the colocated flat runtime once, into a JVM-lifetime temp directory: the
	 * bridge plus all nine Streamline/NGX runtime dlls that must sit beside it on disk for
	 * Windows dependency resolution. Protocol-agnostic — every entry is read off the
	 * classloader, which serves plain jar and nested jar:jar: URLs alike — so the same
	 * extraction serves the SDK jar on a test classpath and the Fabric-nested jar in production.
	 */
	private static Path extractedDirectory() throws IOException {
		final Path cached = extracted;
		if (cached != null && Files.isDirectory(cached)) {
			return cached;
		}

		synchronized (ExtensionBootstrap.class) {
			if (extracted != null && Files.isDirectory(extracted)) {
				return extracted;
			}
			final Path directory = Files.createTempDirectory("mc-dlss-native");
			directory.toFile().deleteOnExit();
			extract(directory, "mc_dlss.dll");
			for (String name : FLAT_RUNTIME_FILES) {
				extract(directory, name);
			}
			extracted = directory;
			return directory;
		}
	}

	private static void extract(final Path directory, final String name) throws IOException {
		try (InputStream source = ExtensionBootstrap.class.getResourceAsStream(RESOURCE_DIRECTORY + name)) {
			if (source == null) {
				throw new IOException("Staged native missing from packaged resources: " + name);
			}
			final Path target = directory.resolve(name);
			Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
			target.toFile().deleteOnExit();
		}
	}
}
