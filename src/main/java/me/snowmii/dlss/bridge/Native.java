package me.snowmii.dlss.bridge;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Flat Java 25 FFM binding. NGX types and ownership stay inside mc_dlss_native. */
public final class Native implements AutoCloseable, NativeApi {
	private static final int SUCCESS = NativeApi.SUCCESS_RESULT;
	private static final ValueLayout.OfInt JAVA_INT = ValueLayout.JAVA_INT;
	private static final ValueLayout.OfLong JAVA_LONG = ValueLayout.JAVA_LONG;
	private static final ValueLayout.OfFloat JAVA_FLOAT = ValueLayout.JAVA_FLOAT;
	private static final Linker LINKER = Linker.nativeLinker();

	/**
	 * {@code McDlssImage}: two 8-byte handles and a 4-byte format.
	 *
	 * <p>The trailing padding is declared rather than left implicit. The fields are 20 bytes and
	 * the struct is 24, because its 8-byte alignment rounds the size up; a layout that omitted
	 * the padding would describe a 20-byte struct and silently misplace every field of any
	 * enclosing struct that follows one.
	 */
	private static final StructLayout IMAGE_LAYOUT = MemoryLayout.structLayout(
		JAVA_LONG.withName("view"),
		JAVA_LONG.withName("image"),
		JAVA_INT.withName("format"),
		MemoryLayout.paddingLayout(4)
	).withName("McDlssImage");

	/** {@code McDlssVec2}: two floats, 8 bytes, 4-byte aligned - no padding of its own. */
	private static final StructLayout VEC2_LAYOUT = MemoryLayout.structLayout(
		JAVA_FLOAT.withName("x"),
		JAVA_FLOAT.withName("y")
	).withName("McDlssVec2");

	private static final StructLayout EVALUATE_LAYOUT = MemoryLayout.structLayout(
		JAVA_LONG.withName("command_buffer"),
		IMAGE_LAYOUT.withName("color"),
		IMAGE_LAYOUT.withName("depth"),
		VEC2_LAYOUT.withName("jitter"),
		VEC2_LAYOUT.withName("motion_scale"),
		JAVA_INT.withName("render_width"),
		JAVA_INT.withName("render_height"),
		JAVA_FLOAT.withName("frame_time_milliseconds"),
		JAVA_INT.withName("reset_history")
	).withName("McDlssEvaluateInfo");

	private static final StructLayout MOTION_LAYOUT = MemoryLayout.structLayout(
		JAVA_LONG.withName("command_buffer"),
		IMAGE_LAYOUT.withName("depth"),
		ValueLayout.ADDRESS.withName("reprojection"),
		JAVA_INT.withName("render_width"),
		JAVA_INT.withName("render_height")
	).withName("McDlssMotionInfo");

	private static final StructLayout PRESENT_LAYOUT = MemoryLayout.structLayout(
		JAVA_LONG.withName("command_buffer"),
		JAVA_LONG.withName("image"),
		JAVA_INT.withName("width"),
		JAVA_INT.withName("height")
	).withName("McDlssPresentInfo");

	private static VarHandle field(final StructLayout layout, final String... path) {
		final MemoryLayout.PathElement[] elements = new MemoryLayout.PathElement[path.length];
		for (int index = 0; index < path.length; index++) {
			elements[index] = MemoryLayout.PathElement.groupElement(path[index]);
		}
		return layout.varHandle(elements);
	}

	private static final VarHandle EVALUATE_COMMAND_BUFFER = field(EVALUATE_LAYOUT, "command_buffer");
	private static final VarHandle EVALUATE_COLOR_VIEW = field(EVALUATE_LAYOUT, "color", "view");
	private static final VarHandle EVALUATE_COLOR_IMAGE = field(EVALUATE_LAYOUT, "color", "image");
	private static final VarHandle EVALUATE_COLOR_FORMAT = field(EVALUATE_LAYOUT, "color", "format");
	private static final VarHandle EVALUATE_DEPTH_VIEW = field(EVALUATE_LAYOUT, "depth", "view");
	private static final VarHandle EVALUATE_DEPTH_IMAGE = field(EVALUATE_LAYOUT, "depth", "image");
	private static final VarHandle EVALUATE_DEPTH_FORMAT = field(EVALUATE_LAYOUT, "depth", "format");
	private static final VarHandle EVALUATE_JITTER_X = field(EVALUATE_LAYOUT, "jitter", "x");
	private static final VarHandle EVALUATE_JITTER_Y = field(EVALUATE_LAYOUT, "jitter", "y");
	private static final VarHandle EVALUATE_MOTION_SCALE_X = field(EVALUATE_LAYOUT, "motion_scale", "x");
	private static final VarHandle EVALUATE_MOTION_SCALE_Y = field(EVALUATE_LAYOUT, "motion_scale", "y");
	private static final VarHandle EVALUATE_RENDER_WIDTH = field(EVALUATE_LAYOUT, "render_width");
	private static final VarHandle EVALUATE_RENDER_HEIGHT = field(EVALUATE_LAYOUT, "render_height");
	private static final VarHandle EVALUATE_FRAME_TIME = field(EVALUATE_LAYOUT, "frame_time_milliseconds");
	private static final VarHandle EVALUATE_RESET_HISTORY = field(EVALUATE_LAYOUT, "reset_history");

	private static final VarHandle MOTION_COMMAND_BUFFER = field(MOTION_LAYOUT, "command_buffer");
	private static final VarHandle MOTION_DEPTH_VIEW = field(MOTION_LAYOUT, "depth", "view");
	private static final VarHandle MOTION_DEPTH_IMAGE = field(MOTION_LAYOUT, "depth", "image");
	private static final VarHandle MOTION_DEPTH_FORMAT = field(MOTION_LAYOUT, "depth", "format");
	private static final VarHandle MOTION_REPROJECTION = field(MOTION_LAYOUT, "reprojection");
	private static final VarHandle MOTION_RENDER_WIDTH = field(MOTION_LAYOUT, "render_width");
	private static final VarHandle MOTION_RENDER_HEIGHT = field(MOTION_LAYOUT, "render_height");

	private static final VarHandle IMAGE_VIEW = field(IMAGE_LAYOUT, "view");
	private static final VarHandle IMAGE_IMAGE = field(IMAGE_LAYOUT, "image");
	private static final VarHandle IMAGE_FORMAT = field(IMAGE_LAYOUT, "format");

	private static final VarHandle PRESENT_COMMAND_BUFFER = field(PRESENT_LAYOUT, "command_buffer");
	private static final VarHandle PRESENT_IMAGE = field(PRESENT_LAYOUT, "image");
	private static final VarHandle PRESENT_WIDTH = field(PRESENT_LAYOUT, "width");
	private static final VarHandle PRESENT_HEIGHT = field(PRESENT_LAYOUT, "height");

	private final Arena arena;
	private final MethodHandle queryInstanceExtension;
	private final MethodHandle queryDeviceExtension;
	private final MethodHandle initialize;
	private final MethodHandle queryOptimalDimensions;
	private final MethodHandle configure;
	private final MethodHandle acquireImages;
	private final MethodHandle releaseImages;
	private final MethodHandle waitDeviceIdle;
	private final MethodHandle queryFrameTimings;
	/** Per-frame reprojection staging, owned by {@link #arena} so no call allocates one. */
	private final MemorySegment reprojectionScratch;
	/**
	 * Per-frame request staging, owned by {@link #arena} for the same reason as the
	 * reprojection: these are written and read once per frame on the render thread, so the
	 * struct lives in one segment rather than a confined Arena allocated per call.
	 */
	private final MemorySegment evaluateScratch;
	private final MemorySegment motionScratch;
	private final MemorySegment presentScratch;
	private final MethodHandle writeMotion;
	private final MethodHandle presentOutput;
	private final MethodHandle evaluate;
	private final MethodHandle reset;
	private final MethodHandle close;
	private boolean closed;

	private Native(final Arena arena, final SymbolLookup lookup) {
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
			FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)
		);
		this.acquireImages = bind(
			lookup,
			"mc_dlss_acquire_images",
			FunctionDescriptor.of(
				JAVA_INT,
				ValueLayout.ADDRESS, // McDlssImage* motion
				ValueLayout.ADDRESS // McDlssImage* output
			)
		);
		this.releaseImages = bind(lookup, "mc_dlss_release_images", FunctionDescriptor.of(JAVA_INT));
		this.waitDeviceIdle = bind(lookup, "mc_dlss_wait_device_idle", FunctionDescriptor.of(JAVA_INT));
		this.queryFrameTimings = bind(
			lookup,
			"mc_dlss_query_frame_timings",
			FunctionDescriptor.of(
				JAVA_INT,
				ValueLayout.ADDRESS, // motion_ms
				ValueLayout.ADDRESS, // evaluate_ms
				ValueLayout.ADDRESS, // present_ms
				ValueLayout.ADDRESS // total_ms
			)
		);
		this.writeMotion = bind(
			lookup,
			"mc_dlss_write_motion",
			FunctionDescriptor.of(JAVA_INT, ValueLayout.ADDRESS) // const McDlssMotionInfo*
		);
		this.presentOutput = bind(
			lookup,
			"mc_dlss_present_output",
			FunctionDescriptor.of(JAVA_INT, ValueLayout.ADDRESS) // const McDlssPresentInfo*
		);
		this.evaluate = bind(
			lookup,
			"mc_dlss_evaluate",
			FunctionDescriptor.of(JAVA_INT, ValueLayout.ADDRESS) // const McDlssEvaluateInfo*
		);
		this.reset = bind(lookup, "mc_dlss_reset", FunctionDescriptor.of(JAVA_INT));
		this.close = bind(lookup, "mc_dlss_close", FunctionDescriptor.of(JAVA_INT));
		this.reprojectionScratch = arena.allocate(JAVA_FLOAT, 16);
		this.evaluateScratch = arena.allocate(EVALUATE_LAYOUT);
		this.motionScratch = arena.allocate(MOTION_LAYOUT);
		this.presentScratch = arena.allocate(PRESENT_LAYOUT);
	}

	public static Native open(final Path libraryPath) {
		Objects.requireNonNull(libraryPath, "libraryPath");
		final Arena arena = Arena.ofShared();
		try {
			return new Native(arena, SymbolLookup.libraryLookup(libraryPath, arena));
		} catch (Throwable error) {
			arena.close();
			throw new NativeException("load-library", error);
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
				throw new NativeException("query-extensions", result);
			}
			final int extensionCount = count.get(JAVA_INT, 0);
			final LinkedHashSet<String> names = new LinkedHashSet<>(extensionCount);
			for (int index = 0; index < extensionCount; index++) {
				final MemorySegment name = callArena.allocate(256);
				result = device
					? (int)this.queryDeviceExtension.invokeExact(vkInstance, vkPhysicalDevice, index, name, 256, count)
					: (int)this.queryInstanceExtension.invokeExact(index, name, 256, count);
				if (result != SUCCESS) {
					throw new NativeException("query-extensions", result);
				}
				names.add(name.getString(0));
			}
			return List.copyOf(names);
		} catch (NativeException error) {
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
				throw new NativeException("query-dimensions", result);
			}
			return new DlssDimensions(renderWidth.get(JAVA_INT, 0), renderHeight.get(JAVA_INT, 0));
		} catch (NativeException error) {
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
		final int qualityMode,
		final int renderPreset
	) {
		try {
			return (int)this.configure.invokeExact(
				outputWidth,
				outputHeight,
				renderWidth,
				renderHeight,
				qualityMode,
				renderPreset
			);
		} catch (Throwable error) {
			throw nativeError("configure", error);
		}
	}

	@Override
	public DlssEvaluationImages acquireImages() {
		try (Arena callArena = Arena.ofConfined()) {
			final MemorySegment motion = callArena.allocate(IMAGE_LAYOUT);
			final MemorySegment output = callArena.allocate(IMAGE_LAYOUT);
			final int result = (int)this.acquireImages.invokeExact(motion, output);
			if (result != SUCCESS) {
				throw new NativeException("acquire-images", result);
			}
			return new DlssEvaluationImages(readImage(motion), readImage(output));
		} catch (NativeException error) {
			throw error;
		} catch (Throwable error) {
			throw nativeError("acquire-images", error);
		}
	}

	/** Reads one {@code McDlssImage} the bridge filled in. */
	private static ImageBinding readImage(final MemorySegment image) {
		return new ImageBinding(
			(long)IMAGE_VIEW.get(image, 0L),
			(long)IMAGE_IMAGE.get(image, 0L),
			(int)IMAGE_FORMAT.get(image, 0L)
		);
	}

	/** Writes one {@code McDlssImage} nested at {@code path} inside {@code target}. */
	private static void writeImage(
		final MemorySegment target,
		final VarHandle view,
		final VarHandle image,
		final VarHandle format,
		final ImageBinding binding
	) {
		view.set(target, 0L, binding.getView());
		image.set(target, 0L, binding.getImage());
		format.set(target, 0L, binding.getFormat());
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
	public int waitDeviceIdle() {
		try {
			return (int)this.waitDeviceIdle.invokeExact();
		} catch (Throwable error) {
			throw nativeError("wait-device-idle", error);
		}
	}

	@Override
	public DlssFrameTimings frameTimings() {
		try (Arena callArena = Arena.ofConfined()) {
			final MemorySegment motion = callArena.allocate(JAVA_FLOAT);
			final MemorySegment evaluate = callArena.allocate(JAVA_FLOAT);
			final MemorySegment present = callArena.allocate(JAVA_FLOAT);
			final MemorySegment total = callArena.allocate(JAVA_FLOAT);
			final int result = (int)this.queryFrameTimings.invokeExact(motion, evaluate, present, total);
			if (result != SUCCESS) {
				// No completed frame yet, or a device that cannot timestamp. Both mean "no
				// measurement", which is not a native failure the session should latch.
				return null;
			}
			return new DlssFrameTimings(
				motion.get(JAVA_FLOAT, 0),
				evaluate.get(JAVA_FLOAT, 0),
				present.get(JAVA_FLOAT, 0),
				total.get(JAVA_FLOAT, 0)
			);
		} catch (Throwable error) {
			throw nativeError("query-frame-timings", error);
		}
	}

	@Override
	public int writeMotion(final MotionRequest request) {
		final float[] reprojection = request.getReprojection();
		if (reprojection.length != 16) {
			throw new IllegalArgumentException("Reprojection must be 16 column-major floats");
		}
		final DlssDimensions render = requireDimensions(request.getRenderDimensions(), "write-motion");
		try {
			final MemorySegment matrix = this.reprojectionScratch;
			MemorySegment.copy(reprojection, 0, matrix, JAVA_FLOAT, 0, reprojection.length);
			final MemorySegment info = this.motionScratch;
			MOTION_COMMAND_BUFFER.set(info, 0L, request.getCommandBuffer());
			writeImage(info, MOTION_DEPTH_VIEW, MOTION_DEPTH_IMAGE, MOTION_DEPTH_FORMAT, request.getDepth());
			MOTION_REPROJECTION.set(info, 0L, matrix);
			MOTION_RENDER_WIDTH.set(info, 0L, render.getWidth());
			MOTION_RENDER_HEIGHT.set(info, 0L, render.getHeight());
			return (int)this.writeMotion.invokeExact(info);
		} catch (Throwable error) {
			throw nativeError("write-motion", error);
		}
	}

	@Override
	public int presentOutput(final PresentTarget target) {
		final DlssDimensions output = requireDimensions(target.getOutputDimensions(), "present-output");
		try {
			final MemorySegment info = this.presentScratch;
			PRESENT_COMMAND_BUFFER.set(info, 0L, target.getCommandBuffer());
			PRESENT_IMAGE.set(info, 0L, target.getImage());
			PRESENT_WIDTH.set(info, 0L, output.getWidth());
			PRESENT_HEIGHT.set(info, 0L, output.getHeight());
			return (int)this.presentOutput.invokeExact(info);
		} catch (Throwable error) {
			throw nativeError("present-output", error);
		}
	}

	@Override
	public int evaluate(final EvaluationRequest request) {
		final DlssDimensions render = requireDimensions(request.getRenderDimensions(), "evaluate");
		try {
			final MemorySegment info = this.evaluateScratch;
			EVALUATE_COMMAND_BUFFER.set(info, 0L, request.getCommandBuffer());
			writeImage(info, EVALUATE_COLOR_VIEW, EVALUATE_COLOR_IMAGE, EVALUATE_COLOR_FORMAT, request.getColor());
			writeImage(info, EVALUATE_DEPTH_VIEW, EVALUATE_DEPTH_IMAGE, EVALUATE_DEPTH_FORMAT, request.getDepth());
			EVALUATE_JITTER_X.set(info, 0L, request.getJitter().getX());
			EVALUATE_JITTER_Y.set(info, 0L, request.getJitter().getY());
			EVALUATE_MOTION_SCALE_X.set(info, 0L, request.getMotionScale().getX());
			EVALUATE_MOTION_SCALE_Y.set(info, 0L, request.getMotionScale().getY());
			EVALUATE_RENDER_WIDTH.set(info, 0L, render.getWidth());
			EVALUATE_RENDER_HEIGHT.set(info, 0L, render.getHeight());
			EVALUATE_FRAME_TIME.set(info, 0L, request.getFrameTimeMilliseconds());
			EVALUATE_RESET_HISTORY.set(info, 0L, request.getResetHistory() ? 1 : 0);
			return (int)this.evaluate.invokeExact(info);
		} catch (Throwable error) {
			throw nativeError("evaluate", error);
		}
	}

	/**
	 * The dimensions the adapter stamps onto a request are what the bridge checks its caller
	 * against, so a request that reached here without them is a wiring mistake rather than a
	 * native failure - it would otherwise be sent as a zero the bridge rejects for the wrong
	 * reason.
	 */
	private static DlssDimensions requireDimensions(final DlssDimensions dimensions, final String stage) {
		if (dimensions == null) {
			throw new IllegalStateException(stage + " requires dimensions stamped by the adapter");
		}
		return dimensions;
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
				throw new NativeException("close", result);
			}
			this.closed = true;
			this.arena.close();
		} catch (NativeException error) {
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

	private static NativeException nativeError(final String stage, final Throwable error) {
		if (error instanceof NativeException nativeError) {
			return nativeError;
		}
		return new NativeException(stage, error);
	}
}
