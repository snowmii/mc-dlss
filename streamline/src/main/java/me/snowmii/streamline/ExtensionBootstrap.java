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
	 * {@code :streamline}'s processResources into {@code assets/streamline-api/native/}.
	 */
	private static final List<String> FLAT_RUNTIME_FILES = List.of(
		"sl.interposer.dll", "sl.common.dll", "sl.dlss.dll", "sl.dlss_g.dll", "sl.reflex.dll",
		"sl.pcl.dll",
		"nvngx_dlss.dll", "nvngx_dlssg.dll", "NvLowLatencyVk.dll"
	);

	private static final Path RELATIVE_STREAMLINE = Path.of("build", "resources", "main", "assets", "streamline-api", "native");

	/**
	 * Knob name for error messages. The mod reads {@code -Dmc.dlss.native-library} in its
	 * config and injects via {@link #setNativeLibraryPath}; this seam runs first.
	 */
	private static final String NATIVE_LIBRARY_PROPERTY = "mc.dlss.native-library";

	private static volatile Path configuredNativeLibrary;
	private static volatile Path extracted;
	private static volatile boolean streamlineRuntimeLoaded;

	private ExtensionBootstrap() {
	}

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

	/** Fresh bridge each call; bootstrap is idempotent. */
	public static List<String> queryDeviceFeatures12() {
		loadStreamlineRuntime();
		try (Native nativeBridge = Native.open(nativeLibrary())) {
			bootstrap(nativeBridge);
			return nativeBridge.queryDeviceFeatures12();
		}
	}

	public static List<String> queryDeviceFeatures13() {
		loadStreamlineRuntime();
		try (Native nativeBridge = Native.open(nativeLibrary())) {
			bootstrap(nativeBridge);
			return nativeBridge.queryDeviceFeatures13();
		}
	}

	/**
	 * Extra graphics/compute/optical-flow queues Streamline requires. Optical flow is
	 * reported but never created: without a host family, DLSS-G runs interop.
	 */
	public static SlQueueRequirements queryQueueRequirements() {
		loadStreamlineRuntime();
		try (Native nativeBridge = Native.open(nativeLibrary())) {
			bootstrap(nativeBridge);
			return nativeBridge.queryQueueRequirements();
		}
	}

	/**
	 * After VulkanDevice construction. Streamline state survives this bridge close.
	 * Throws {@link StreamlineException} if slSetVulkanInfo fails.
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
				throw new StreamlineException("activate-vulkan-proxies", result);
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
			throw new StreamlineException("bootstrap-streamline", result);
		}
	}

	/**
	 * Loose classpath file: load in place. Packaged (plain or nested jar:jar:): extract once
	 * as the colocated flat directory. No staged resource: walk up from cwd for {@code :streamline}
	 * processResources output.
	 */
	public static Path streamlineRuntimeDirectory() {
		final URL resource = ExtensionBootstrap.class.getResource(STREAMLINE_RESOURCE_PATH);
		if (resource != null && "file".equals(resource.getProtocol())) {
			try {
				return Path.of(resource.toURI()).getParent();
			} catch (URISyntaxException error) {
				throw new StreamlineException("bootstrap-streamline", error);
			}
		}
		if (resource != null) {
			try {
				return extractedDirectory();
			} catch (IOException error) {
				throw new StreamlineException("bootstrap-streamline", error);
			}
		}
		for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
			final Path candidate = directory.resolve(RELATIVE_STREAMLINE);
			if (Files.isRegularFile(candidate.resolve("sl.interposer.dll"))) return candidate;
		}
		throw new StreamlineException("bootstrap-streamline", new IllegalStateException("Staged Streamline runtime not found; run ./gradlew.bat processResources"));
	}

	/**
	 * Injected by the mod at {@code onInitialize}, before any query. Null resets to packaged
	 * resolution. This seam runs before the mod's config handle exists.
	 */
	public static void setNativeLibraryPath(final Path path) {
		configuredNativeLibrary = path;
	}

	/**
	 * Injected path first, then packaged resource. No working-directory or build-directory
	 * walk-up: the dev client cwd is {@code run/}, tests run from the repo root.
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

		throw new StreamlineException(
			"load-library",
			new IllegalStateException(
				"Native DLSS bridge not found. Run ./gradlew.bat :streamline:buildNativeDlss, or set -D"
					+ NATIVE_LIBRARY_PROPERTY + "=<path to mc_dlss.dll>. Tried:\n  classpath:" + RESOURCE_PATH
			)
		);
	}

	/**
	 * {@link java.lang.foreign.SymbolLookup#libraryLookup(Path, Arena)} cannot load from a jar.
	 * Loose classpath file: in place. Packaged: extract once with the colocated flat directory.
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
				throw new StreamlineException("load-library", error);
			}
		}

		try {
			return extractedDirectory().resolve("mc_dlss.dll");
		} catch (IOException error) {
			throw new StreamlineException("load-library", error);
		}
	}

	/**
	 * JVM-lifetime temp dir: bridge plus colocated Streamline/NGX dlls (Windows load-from-same-
	 * directory). Classloader-served, so the same extract covers a test classpath jar and the
	 * Fabric-nested jar.
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
