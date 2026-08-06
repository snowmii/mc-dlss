package me.snowmii.dlss;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Flat Java 25 FFM binding. NGX types and ownership stay inside mc_dlss_native. */
public final class DlssNative implements AutoCloseable, DlssNativeApi {
	private static final int SUCCESS = DlssNativeApi.SUCCESS_RESULT;
	private static final ValueLayout.OfInt JAVA_INT = ValueLayout.JAVA_INT;
	private static final ValueLayout.OfLong JAVA_LONG = ValueLayout.JAVA_LONG;
	private static final ValueLayout.OfFloat JAVA_FLOAT = ValueLayout.JAVA_FLOAT;
	private static final Linker LINKER = Linker.nativeLinker();

	private final Arena arena;
	private final MethodHandle queryInstanceExtension;
	private final MethodHandle queryDeviceExtension;
	private final MethodHandle initialize;
	private final MethodHandle queryOptimalDimensions;
	private final MethodHandle configure;
	private final MethodHandle acquireImages;
	private final MethodHandle releaseImages;
	private final MethodHandle evaluate;
	private final MethodHandle reset;
	private final MethodHandle close;
	private boolean closed;

	private DlssNative(final Arena arena, final SymbolLookup lookup) {
		this.arena = arena;
		this.queryInstanceExtension = bind(
			lookup,
			"mc_dlss_query_instance_extension",
			FunctionDescriptor.of(JAVA_INT, JAVA_INT, ValueLayout.ADDRESS, JAVA_INT, ValueLayout.ADDRESS)
		);
		this.queryDeviceExtension = bind(
			lookup,
			"mc_dlss_query_device_extension",
			FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, ValueLayout.ADDRESS, JAVA_INT, ValueLayout.ADDRESS)
		);
		this.initialize = bind(
			lookup,
			"mc_dlss_initialize",
			FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
		);
		this.queryOptimalDimensions = bind(
			lookup,
			"mc_dlss_query_optimal_dimensions",
			FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
		);
		this.configure = bind(
			lookup,
			"mc_dlss_configure",
			FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)
		);
		this.acquireImages = bind(
			lookup,
			"mc_dlss_acquire_images",
			FunctionDescriptor.of(
				JAVA_INT,
				ValueLayout.ADDRESS, // motion_image
				ValueLayout.ADDRESS, // motion_view
				ValueLayout.ADDRESS, // motion_format
				ValueLayout.ADDRESS, // output_image
				ValueLayout.ADDRESS, // output_view
				ValueLayout.ADDRESS // output_format
			)
		);
		this.releaseImages = bind(lookup, "mc_dlss_release_images", FunctionDescriptor.of(JAVA_INT));
		this.evaluate = bind(
			lookup,
			"mc_dlss_evaluate",
			FunctionDescriptor.of(
				JAVA_INT,
				JAVA_LONG, // command_buffer
				JAVA_LONG, // color_view
				JAVA_LONG, // color_image
				JAVA_INT, // color_format
				JAVA_INT, // color_aspect_mask
				JAVA_INT, // color_base_mip_level
				JAVA_INT, // color_level_count
				JAVA_INT, // color_base_array_layer
				JAVA_INT, // color_layer_count
				JAVA_LONG, // depth_view
				JAVA_LONG, // depth_image
				JAVA_INT, // depth_format
				JAVA_INT, // depth_aspect_mask
				JAVA_INT, // depth_base_mip_level
				JAVA_INT, // depth_level_count
				JAVA_INT, // depth_base_array_layer
				JAVA_INT, // depth_layer_count
				JAVA_LONG, // motion_view
				JAVA_LONG, // motion_image
				JAVA_INT, // motion_format
				JAVA_INT, // motion_aspect_mask
				JAVA_INT, // motion_base_mip_level
				JAVA_INT, // motion_level_count
				JAVA_INT, // motion_base_array_layer
				JAVA_INT, // motion_layer_count
				JAVA_LONG, // output_view
				JAVA_LONG, // output_image
				JAVA_INT, // output_format
				JAVA_INT, // output_aspect_mask
				JAVA_INT, // output_base_mip_level
				JAVA_INT, // output_level_count
				JAVA_INT, // output_base_array_layer
				JAVA_INT, // output_layer_count
				JAVA_INT, // render_width
				JAVA_INT, // render_height
				JAVA_INT, // output_width
				JAVA_INT, // output_height
				JAVA_FLOAT,
				JAVA_FLOAT,
				JAVA_FLOAT,
				JAVA_FLOAT,
				JAVA_FLOAT,
				JAVA_INT
			)
		);
		this.reset = bind(lookup, "mc_dlss_reset", FunctionDescriptor.of(JAVA_INT));
		this.close = bind(lookup, "mc_dlss_close", FunctionDescriptor.of(JAVA_INT));
	}

	public static DlssNative open(final Path libraryPath) {
		Objects.requireNonNull(libraryPath, "libraryPath");
		final Arena arena = Arena.ofShared();
		try {
			return new DlssNative(arena, SymbolLookup.libraryLookup(libraryPath, arena));
		} catch (Throwable error) {
			arena.close();
			throw new DlssNativeException("load-library", error);
		}
	}

	public List<String> queryInstanceExtensions() {
		return queryExtensions(0L, 0L, false);
	}

	public List<String> queryDeviceExtensions(final long vkInstance, final long vkPhysicalDevice) {
		if (vkInstance == 0L || vkPhysicalDevice == 0L) {
			throw new IllegalArgumentException("Vulkan instance and physical device must be non-zero");
		}
		return queryExtensions(vkInstance, vkPhysicalDevice, true);
	}

	private List<String> queryExtensions(final long vkInstance, final long vkPhysicalDevice, final boolean device) {
		try (Arena callArena = Arena.ofConfined()) {
			final MemorySegment count = callArena.allocate(JAVA_INT);
			int result = device
				? (int)this.queryDeviceExtension.invokeExact(vkInstance, vkPhysicalDevice, 0, MemorySegment.NULL, 0, count)
				: (int)this.queryInstanceExtension.invokeExact(0, MemorySegment.NULL, 0, count);
			if (result != SUCCESS) {
				throw new DlssNativeException("query-extensions", result);
			}
			final int extensionCount = count.get(JAVA_INT, 0);
			final LinkedHashSet<String> names = new LinkedHashSet<>(extensionCount);
			for (int index = 0; index < extensionCount; index++) {
				final MemorySegment name = callArena.allocate(256);
				result = device
					? (int)this.queryDeviceExtension.invokeExact(vkInstance, vkPhysicalDevice, index, name, 256, count)
					: (int)this.queryInstanceExtension.invokeExact(index, name, 256, count);
				if (result != SUCCESS) {
					throw new DlssNativeException("query-extensions", result);
				}
				names.add(name.getString(0));
			}
			return List.copyOf(names);
		} catch (DlssNativeException error) {
			throw error;
		} catch (Throwable error) {
			throw nativeError("query-extensions", error);
		}
	}

	@Override
	public int initialize(
		final long vkInstance,
		final long vkPhysicalDevice,
		final long vkDevice,
		final Path sdkPath,
		final Path dataPath
	) {
		try (Arena callArena = Arena.ofConfined()) {
			final MemorySegment sdk = callArena.allocateFrom(sdkPath.toString());
			final MemorySegment data = callArena.allocateFrom(dataPath.toString());
			return (int)this.initialize.invokeExact(vkInstance, vkPhysicalDevice, vkDevice, sdk, data);
		} catch (Throwable error) {
			throw nativeError("initialize", error);
		}
	}

	@Override
	public DlssDimensions queryOptimalDimensions(
		final int outputWidth,
		final int outputHeight,
		final int qualityMode
	) {
		try (Arena callArena = Arena.ofConfined()) {
			final MemorySegment renderWidth = callArena.allocate(JAVA_INT);
			final MemorySegment renderHeight = callArena.allocate(JAVA_INT);
			final int result = (int)this.queryOptimalDimensions.invokeExact(
				outputWidth,
				outputHeight,
				qualityMode,
				renderWidth,
				renderHeight
			);
			if (result != SUCCESS) {
				throw new DlssNativeException("query-dimensions", result);
			}
			return new DlssDimensions(renderWidth.get(JAVA_INT, 0), renderHeight.get(JAVA_INT, 0));
		} catch (DlssNativeException error) {
			throw error;
		} catch (Throwable error) {
			throw nativeError("query-dimensions", error);
		}
	}

	@Override
	public int configure(
		final int outputWidth,
		final int outputHeight,
		final int renderWidth,
		final int renderHeight,
		final int qualityMode
	) {
		try {
			return (int)this.configure.invokeExact(outputWidth, outputHeight, renderWidth, renderHeight, qualityMode);
		} catch (Throwable error) {
			throw nativeError("configure", error);
		}
	}

	@Override
	public DlssEvaluationImages acquireImages() {
		try (Arena callArena = Arena.ofConfined()) {
			final MemorySegment motionImage = callArena.allocate(JAVA_LONG);
			final MemorySegment motionView = callArena.allocate(JAVA_LONG);
			final MemorySegment motionFormat = callArena.allocate(JAVA_INT);
			final MemorySegment outputImage = callArena.allocate(JAVA_LONG);
			final MemorySegment outputView = callArena.allocate(JAVA_LONG);
			final MemorySegment outputFormat = callArena.allocate(JAVA_INT);
			final int result = (int)this.acquireImages.invokeExact(
				motionImage,
				motionView,
				motionFormat,
				outputImage,
				outputView,
				outputFormat
			);
			if (result != SUCCESS) {
				throw new DlssNativeException("acquire-images", result);
			}
			return new DlssEvaluationImages(
				motionImage.get(JAVA_LONG, 0),
				motionView.get(JAVA_LONG, 0),
				motionFormat.get(JAVA_INT, 0),
				outputImage.get(JAVA_LONG, 0),
				outputView.get(JAVA_LONG, 0),
				outputFormat.get(JAVA_INT, 0)
			);
		} catch (DlssNativeException error) {
			throw error;
		} catch (Throwable error) {
			throw nativeError("acquire-images", error);
		}
	}

	@Override
	public int releaseImages() {
		try {
			return (int)this.releaseImages.invokeExact();
		} catch (Throwable error) {
			throw nativeError("release-images", error);
		}
	}

	@Override
	public int evaluate(
		final long commandBuffer,
		final long colorView,
		final long colorImage,
		final int colorFormat,
		final int colorAspectMask,
		final int colorBaseMipLevel,
		final int colorLevelCount,
		final int colorBaseArrayLayer,
		final int colorLayerCount,
		final long depthView,
		final long depthImage,
		final int depthFormat,
		final int depthAspectMask,
		final int depthBaseMipLevel,
		final int depthLevelCount,
		final int depthBaseArrayLayer,
		final int depthLayerCount,
		final long motionView,
		final long motionImage,
		final int motionFormat,
		final int motionAspectMask,
		final int motionBaseMipLevel,
		final int motionLevelCount,
		final int motionBaseArrayLayer,
		final int motionLayerCount,
		final long outputView,
		final long outputImage,
		final int outputFormat,
		final int outputAspectMask,
		final int outputBaseMipLevel,
		final int outputLevelCount,
		final int outputBaseArrayLayer,
		final int outputLayerCount,
		final int renderWidth,
		final int renderHeight,
		final int outputWidth,
		final int outputHeight,
		final float jitterX,
		final float jitterY,
		final float motionScaleX,
		final float motionScaleY,
		final float frameTimeMilliseconds,
		final boolean resetHistory
	) {
		try {
			return (int)this.evaluate.invokeExact(
				commandBuffer,
				colorView,
				colorImage,
				colorFormat,
				colorAspectMask,
				colorBaseMipLevel,
				colorLevelCount,
				colorBaseArrayLayer,
				colorLayerCount,
				depthView,
				depthImage,
				depthFormat,
				depthAspectMask,
				depthBaseMipLevel,
				depthLevelCount,
				depthBaseArrayLayer,
				depthLayerCount,
				motionView,
				motionImage,
				motionFormat,
				motionAspectMask,
				motionBaseMipLevel,
				motionLevelCount,
				motionBaseArrayLayer,
				motionLayerCount,
				outputView,
				outputImage,
				outputFormat,
				outputAspectMask,
				outputBaseMipLevel,
				outputLevelCount,
				outputBaseArrayLayer,
				outputLayerCount,
				renderWidth,
				renderHeight,
				outputWidth,
				outputHeight,
				jitterX,
				jitterY,
				motionScaleX,
				motionScaleY,
				frameTimeMilliseconds,
				resetHistory ? 1 : 0
			);
		} catch (Throwable error) {
			throw nativeError("evaluate", error);
		}
	}

	public int reset() {
		try {
			return (int)this.reset.invokeExact();
		} catch (Throwable error) {
			throw nativeError("reset", error);
		}
	}

	@Override
	public synchronized void close() {
		if (this.closed) {
			return;
		}

		try {
			final int result = (int)this.close.invokeExact();
			if (result != SUCCESS) {
				// Keep arena and downcall handles alive so native shutdown can be retried.
				throw new DlssNativeException("close", result);
			}
			this.closed = true;
			this.arena.close();
		} catch (DlssNativeException error) {
			throw error;
		} catch (Throwable error) {
			throw nativeError("close", error);
		}
	}

	private static MethodHandle bind(
		final SymbolLookup lookup,
		final String symbol,
		final FunctionDescriptor descriptor
	) {
		return LINKER.downcallHandle(
			lookup.find(symbol).orElseThrow(() -> new IllegalStateException("Missing native symbol: " + symbol)),
			descriptor
		);
	}

	private static DlssNativeException nativeError(final String stage, final Throwable error) {
		if (error instanceof DlssNativeException nativeError) {
			return nativeError;
		}
		return new DlssNativeException(stage, error);
	}
}
