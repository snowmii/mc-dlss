package me.snowmii.streamline;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Flat Java 25 FFM binding. NGX types and ownership stay inside mc_dlss_native. */
public final class Native implements AutoCloseable, NativeApi {
	private static final int SUCCESS = NativeApi.SUCCESS_RESULT;
	private static final ValueLayout.OfInt JAVA_INT = ValueLayout.JAVA_INT;
	private static final ValueLayout.OfLong JAVA_LONG = ValueLayout.JAVA_LONG;
	private static final ValueLayout.OfFloat JAVA_FLOAT = ValueLayout.JAVA_FLOAT;
	private static final Linker LINKER = Linker.nativeLinker();

	/**
	 * One native-library lookup per absolute path, each pinned for the JVM lifetime.
	 *
	 * <p>The bootstrap/query/activation bridges ExtensionBootstrap opens close before the
	 * runtime bridge opens, and the native module's globals (Streamline bootstrap state, the
	 * activated proxy tuple) have to survive those closes: Streamline stays process-wide, and
	 * a module instance that unloaded with its arena would lose the tuple the runtime bridge's
	 * {@code mc_dlss_initialize} must match. Pinning the lookup with {@link Arena#global()}
	 * keeps the native module loaded, the path key keeps it loaded exactly once, and each
	 * {@link Native} still owns its scratch arena and close behavior.
	 */
	private static final Map<Path, SymbolLookup> PINNED_LOOKUPS = new ConcurrentHashMap<>();

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

	private static final StructLayout CAMERA_LAYOUT = MemoryLayout.structLayout(
		MemoryLayout.sequenceLayout(16, JAVA_FLOAT).withName("view_to_clip"),
		MemoryLayout.sequenceLayout(16, JAVA_FLOAT).withName("clip_to_view"),
		MemoryLayout.sequenceLayout(16, JAVA_FLOAT).withName("clip_to_prev_clip"),
		MemoryLayout.sequenceLayout(16, JAVA_FLOAT).withName("prev_clip_to_clip"),
		MemoryLayout.sequenceLayout(3, JAVA_FLOAT).withName("pos"),
		MemoryLayout.sequenceLayout(3, JAVA_FLOAT).withName("right"),
		MemoryLayout.sequenceLayout(3, JAVA_FLOAT).withName("up"),
		MemoryLayout.sequenceLayout(3, JAVA_FLOAT).withName("fwd"),
		JAVA_FLOAT.withName("near_plane"),
		JAVA_FLOAT.withName("far_plane"),
		JAVA_FLOAT.withName("fov_radians"),
		JAVA_FLOAT.withName("aspect_ratio"),
		VEC2_LAYOUT.withName("jitter")
	).withName("McDlssCameraConstants");

	private static final StructLayout EVALUATE_LAYOUT = MemoryLayout.structLayout(
		JAVA_LONG.withName("command_buffer"),
		IMAGE_LAYOUT.withName("color"),
		IMAGE_LAYOUT.withName("depth"),
		VEC2_LAYOUT.withName("jitter"),
		VEC2_LAYOUT.withName("motion_scale"),
		JAVA_INT.withName("render_width"),
		JAVA_INT.withName("render_height"),
		JAVA_FLOAT.withName("frame_time_milliseconds"),
		JAVA_INT.withName("reset_history"),
		CAMERA_LAYOUT.withName("camera")
	).withName("McDlssEvaluateInfo");

	private static final StructLayout MOTION_LAYOUT = MemoryLayout.structLayout(
		JAVA_LONG.withName("command_buffer"),
		IMAGE_LAYOUT.withName("depth"),
		ValueLayout.ADDRESS.withName("reprojection"),
		JAVA_INT.withName("render_width"),
		JAVA_INT.withName("render_height")
	).withName("McDlssMotionInfo");

	/**
	 * {@code McDlssFillVelocityInfo}: command buffer, depth, velocity companion, reprojection
	 * pointer, render size, and the reset flag.
	 *
	 * <p>76 bytes of fields in 80 bytes of struct: the 8-byte alignment of the pointer rounds
	 * the size up, and the trailing padding is declared explicitly like the image struct's.
	 */
	private static final StructLayout FILL_LAYOUT = MemoryLayout.structLayout(
		JAVA_LONG.withName("command_buffer"),
		IMAGE_LAYOUT.withName("depth"),
		IMAGE_LAYOUT.withName("velocity"),
		ValueLayout.ADDRESS.withName("reprojection"),
		JAVA_INT.withName("render_width"),
		JAVA_INT.withName("render_height"),
		JAVA_INT.withName("reset"),
		MemoryLayout.paddingLayout(4)
	).withName("McDlssFillVelocityInfo");

	private static final StructLayout PRESENT_LAYOUT = MemoryLayout.structLayout(
		JAVA_LONG.withName("command_buffer"),
		JAVA_LONG.withName("image"),
		JAVA_INT.withName("width"),
		JAVA_INT.withName("height")
	).withName("McDlssPresentInfo");

	/**
	 * {@code McDlssTagInfo}: the caller's command buffer followed by two {@code McDlssImage}
	 * structs, 56 bytes with no padding of its own. The motion source is never carried: the
	 * native side always tags the module's own motion image.
	 */
	private static final StructLayout TAG_LAYOUT = MemoryLayout.structLayout(
		JAVA_LONG.withName("command_buffer"),
		IMAGE_LAYOUT.withName("color"),
		IMAGE_LAYOUT.withName("depth")
	).withName("McDlssTagInfo");

	/**
	 * {@code McDlssFgTagInfo}: the caller's command buffer followed by three {@code McDlssImage}
	 * structs - depth, HUD-less colour, UI colour+alpha - 80 bytes with no padding of its own.
	 * The motion source is never carried: the native side always tags the module's own motion
	 * image, like the SR tag does.
	 */
	private static final StructLayout FG_TAG_LAYOUT = MemoryLayout.structLayout(
		JAVA_LONG.withName("command_buffer"),
		IMAGE_LAYOUT.withName("depth"),
		IMAGE_LAYOUT.withName("hudless"),
		IMAGE_LAYOUT.withName("ui")
	).withName("McDlssFgTagInfo");

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

	/**
	 * The byte offset of the camera struct inside {@link #EVALUATE_LAYOUT}, for the
	 * offset-addressed float writes of the camera's six fields.
	 */
	private static final long EVALUATE_CAMERA_OFFSET = EVALUATE_LAYOUT.byteOffset(
		MemoryLayout.PathElement.groupElement("camera")
	);

	/**
	 * The byte offset of one float inside a camera field, relative to the camera struct start:
	 * the field name followed by its index in the field's float sequence.
	 */
	private static long cameraFloatOffset(final String field, final long index) {
		return CAMERA_LAYOUT.byteOffset(
			MemoryLayout.PathElement.groupElement(field),
			MemoryLayout.PathElement.sequenceElement(index)
		);
	}

	/** The byte offset of a camera field's first float inside {@link #EVALUATE_LAYOUT}. */
	private static long evaluateCameraFieldOffset(final String field) {
		return EVALUATE_CAMERA_OFFSET + cameraFloatOffset(field, 0);
	}

	/** The byte offset of one scalar camera field inside {@link #EVALUATE_LAYOUT}. */
	private static long evaluateCameraScalarOffset(final String field) {
		return EVALUATE_CAMERA_OFFSET + CAMERA_LAYOUT.byteOffset(
			MemoryLayout.PathElement.groupElement(field)
		);
	}

	private static final VarHandle MOTION_COMMAND_BUFFER = field(MOTION_LAYOUT, "command_buffer");
	private static final VarHandle MOTION_DEPTH_VIEW = field(MOTION_LAYOUT, "depth", "view");
	private static final VarHandle MOTION_DEPTH_IMAGE = field(MOTION_LAYOUT, "depth", "image");
	private static final VarHandle MOTION_DEPTH_FORMAT = field(MOTION_LAYOUT, "depth", "format");
	private static final VarHandle MOTION_REPROJECTION = field(MOTION_LAYOUT, "reprojection");
	private static final VarHandle MOTION_RENDER_WIDTH = field(MOTION_LAYOUT, "render_width");
	private static final VarHandle MOTION_RENDER_HEIGHT = field(MOTION_LAYOUT, "render_height");

	private static final VarHandle FILL_COMMAND_BUFFER = field(FILL_LAYOUT, "command_buffer");
	private static final VarHandle FILL_DEPTH_VIEW = field(FILL_LAYOUT, "depth", "view");
	private static final VarHandle FILL_DEPTH_IMAGE = field(FILL_LAYOUT, "depth", "image");
	private static final VarHandle FILL_DEPTH_FORMAT = field(FILL_LAYOUT, "depth", "format");
	private static final VarHandle FILL_VELOCITY_VIEW = field(FILL_LAYOUT, "velocity", "view");
	private static final VarHandle FILL_VELOCITY_IMAGE = field(FILL_LAYOUT, "velocity", "image");
	private static final VarHandle FILL_VELOCITY_FORMAT = field(FILL_LAYOUT, "velocity", "format");
	private static final VarHandle FILL_REPROJECTION = field(FILL_LAYOUT, "reprojection");
	private static final VarHandle FILL_RENDER_WIDTH = field(FILL_LAYOUT, "render_width");
	private static final VarHandle FILL_RENDER_HEIGHT = field(FILL_LAYOUT, "render_height");
	private static final VarHandle FILL_RESET = field(FILL_LAYOUT, "reset");

	private static final VarHandle IMAGE_VIEW = field(IMAGE_LAYOUT, "view");
	private static final VarHandle IMAGE_IMAGE = field(IMAGE_LAYOUT, "image");
	private static final VarHandle IMAGE_FORMAT = field(IMAGE_LAYOUT, "format");

	private static final VarHandle PRESENT_COMMAND_BUFFER = field(PRESENT_LAYOUT, "command_buffer");
	private static final VarHandle PRESENT_IMAGE = field(PRESENT_LAYOUT, "image");
	private static final VarHandle PRESENT_WIDTH = field(PRESENT_LAYOUT, "width");
	private static final VarHandle PRESENT_HEIGHT = field(PRESENT_LAYOUT, "height");

	private static final VarHandle TAG_COMMAND_BUFFER = field(TAG_LAYOUT, "command_buffer");
	private static final VarHandle TAG_COLOR_VIEW = field(TAG_LAYOUT, "color", "view");
	private static final VarHandle TAG_COLOR_IMAGE = field(TAG_LAYOUT, "color", "image");
	private static final VarHandle TAG_COLOR_FORMAT = field(TAG_LAYOUT, "color", "format");
	private static final VarHandle TAG_DEPTH_VIEW = field(TAG_LAYOUT, "depth", "view");
	private static final VarHandle TAG_DEPTH_IMAGE = field(TAG_LAYOUT, "depth", "image");
	private static final VarHandle TAG_DEPTH_FORMAT = field(TAG_LAYOUT, "depth", "format");

	private static final VarHandle FG_TAG_COMMAND_BUFFER = field(FG_TAG_LAYOUT, "command_buffer");
	private static final VarHandle FG_TAG_DEPTH_VIEW = field(FG_TAG_LAYOUT, "depth", "view");
	private static final VarHandle FG_TAG_DEPTH_IMAGE = field(FG_TAG_LAYOUT, "depth", "image");
	private static final VarHandle FG_TAG_DEPTH_FORMAT = field(FG_TAG_LAYOUT, "depth", "format");
	private static final VarHandle FG_TAG_HUDLESS_VIEW = field(FG_TAG_LAYOUT, "hudless", "view");
	private static final VarHandle FG_TAG_HUDLESS_IMAGE = field(FG_TAG_LAYOUT, "hudless", "image");
	private static final VarHandle FG_TAG_HUDLESS_FORMAT = field(FG_TAG_LAYOUT, "hudless", "format");
	private static final VarHandle FG_TAG_UI_VIEW = field(FG_TAG_LAYOUT, "ui", "view");
	private static final VarHandle FG_TAG_UI_IMAGE = field(FG_TAG_LAYOUT, "ui", "image");
	private static final VarHandle FG_TAG_UI_FORMAT = field(FG_TAG_LAYOUT, "ui", "format");

	private final Arena nativeArena;
	private final MethodHandle bootstrapStreamline;
	private final MethodHandle activateVulkanProxies;
	private final MethodHandle queryInstanceExtension;
	private final MethodHandle queryDeviceExtension;
	private final MethodHandle queryDeviceFeature12;
	private final MethodHandle queryDeviceFeature13;
	private final MethodHandle queryQueueRequirements;
	private final MethodHandle queryTaggedFrameIndexes;
	private final MethodHandle queryPresentMarkers;
	private final MethodHandle installPclWindow;
	private final MethodHandle reflexInputSample;
	private final MethodHandle reflexMarker;
	private final MethodHandle queryReflexMarkers;
	private final MethodHandle queryReflexOptions;
	private final MethodHandle waitFgInputsValue;
	private final MethodHandle queryFgState;
	private final MethodHandle queryCameraConstants;
	private final MethodHandle queryFgCameraConstants;
	private final MethodHandle queryFgImages;
	private final MethodHandle initialize;
	private final MethodHandle queryOptimalDimensions;
	private final MethodHandle configureSuperResolution;
	private final MethodHandle configureFg;
	private final MethodHandle setFgMode;
	private final MethodHandle setFgMultiplier;
	private final MethodHandle queryFgMultiplier;
	private final MethodHandle acquireImages;
	private final MethodHandle releaseImages;
	private final MethodHandle waitDeviceIdle;
	private final MethodHandle queryFrameTimings;
	/** Per-frame reprojection staging, owned by {@link #nativeArena} so no call allocates one. */
	private final MemorySegment reprojectionScratch;
	/**
	 * Per-frame request staging, owned by {@link #nativeArena} for the same reason as the
	 * reprojection: these are written and read once per frame on the render thread, so the
	 * struct lives in one segment rather than a confined Arena allocated per call.
	 */
	private final MemorySegment evaluateScratch;
	private final MemorySegment motionScratch;
	private final MemorySegment fillScratch;
	private final MemorySegment presentScratch;
	private final MemorySegment tagScratch;
	private final MemorySegment fgTagScratch;
	private final MethodHandle writeMotion;
	private final MethodHandle fillVelocity;
	private final MethodHandle presentOutput;
	private final MethodHandle evaluateSuperResolution;
	private final MethodHandle tagSrResources;
	private final MethodHandle tagFgResources;
	private final MethodHandle presentHandoff;
	private final MethodHandle presentStart;
	private final MethodHandle presentEnd;
	private final MethodHandle waitFgInputsIdle;
	private final MethodHandle recordReflexFrameLimit;
	private final MethodHandle reset;
	private final MethodHandle close;
	private boolean closed;

	private Native(final Arena arena, final SymbolLookup lookup) {
		this.nativeArena = arena;
		this.bootstrapStreamline = bindOptional(
			lookup,
			"mc_dlss_bootstrap_streamline",
			FunctionDescriptor.of(JAVA_INT, ValueLayout.ADDRESS)
		);
		this.activateVulkanProxies = bind(
			lookup,
			"mc_dlss_activate_vulkan_proxies",
			FunctionDescriptor.of(
				JAVA_INT,
				JAVA_LONG, // vk_instance
				JAVA_LONG, // vk_physical_device
				JAVA_LONG, // vk_device
				JAVA_INT, // graphics_queue_family
				JAVA_INT, // graphics_queue_index
				JAVA_INT, // compute_queue_family
				JAVA_INT // compute_queue_index
			)
		);
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
		this.queryDeviceFeature12 = bind(
			lookup,
			"mc_dlss_query_device_feature_12",
			FunctionDescriptor.of(JAVA_INT, JAVA_INT, ValueLayout.ADDRESS, JAVA_INT, ValueLayout.ADDRESS)
		);
		this.queryDeviceFeature13 = bind(
			lookup,
			"mc_dlss_query_device_feature_13",
			FunctionDescriptor.of(JAVA_INT, JAVA_INT, ValueLayout.ADDRESS, JAVA_INT, ValueLayout.ADDRESS)
		);
		this.queryQueueRequirements = bind(
			lookup,
			"mc_dlss_query_queue_requirements",
			FunctionDescriptor.of(JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
		);
		// Optional like tagFgResources: the ABI-probe DLL the layout tests compile stubs the
		// historical ABI surface and does not export the frame-index oracle yet, while every
		// real build since this symbol exists carries it.
		this.queryTaggedFrameIndexes = bindOptional(
			lookup,
			"mc_dlss_query_tagged_frame_indexes",
			FunctionDescriptor.of(JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
		);
		// Optional like queryTaggedFrameIndexes: the ABI-probe DLL does not export the
		// present-marker oracle either, while every real build since this symbol exists
		// carries it.
		this.queryPresentMarkers = bindOptional(
			lookup,
			"mc_dlss_query_present_markers",
			FunctionDescriptor.of(
				JAVA_INT,
				ValueLayout.ADDRESS, // start_count
				ValueLayout.ADDRESS, // end_count
				ValueLayout.ADDRESS, // event_count
				ValueLayout.ADDRESS, // events
				JAVA_INT // events_capacity
			)
		);
		// Optional like queryPresentMarkers: the ABI-probe DLL does not export the PCL window
		// hook or marker entries, while real builds carry them.
		this.installPclWindow = bindOptional(
			lookup,
			"mc_dlss_install_pcl_window",
			FunctionDescriptor.of(JAVA_INT, JAVA_LONG)
		);
		this.reflexInputSample = bindOptional(lookup, "mc_dlss_reflex_input_sample", FunctionDescriptor.of(JAVA_INT));
		this.reflexMarker = bindOptional(lookup, "mc_dlss_reflex_marker", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
		this.queryReflexMarkers = bindOptional(
			lookup,
			"mc_dlss_query_reflex_markers",
			FunctionDescriptor.of(
				JAVA_INT,
				ValueLayout.ADDRESS, // type_counts
				ValueLayout.ADDRESS, // event_count
				ValueLayout.ADDRESS, // events
				JAVA_INT // events_capacity
			)
		);
		// Optional like queryReflexMarkers: the ABI-probe DLL does not export the reflex
		// options oracle either, while every real build since this symbol exists carries it.
		this.queryReflexOptions = bindOptional(
			lookup,
			"mc_dlss_query_reflex_options",
			FunctionDescriptor.of(JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
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
		this.configureSuperResolution = bind(
			lookup,
			"mc_dlss_configure",
			FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)
		);
		// Optional like bootstrapStreamline: the ABI-probe DLL the layout tests compile stubs
		// the historical ABI surface and does not export the FG record yet, while every real
		// build since this symbol exists carries it.
		this.configureFg = bindOptional(
			lookup,
			"mc_dlss_configure_fg",
			FunctionDescriptor.of(JAVA_INT, JAVA_INT) // num_back_buffers
		);
		// Optional like configureFg: the ABI-probe DLL the layout tests compile stubs
		// the historical ABI surface and does not export the FG mode record yet, while
		// every real build since this symbol exists carries it.
		this.setFgMode = bindOptional(
			lookup,
			"mc_dlss_set_fg_mode",
			FunctionDescriptor.of(JAVA_INT, JAVA_INT) // fg_enabled
		);
		// Optional like setFgMode: the ABI-probe DLL does not export the multiplier record
		// either, while every real build since this symbol exists carries it.
		this.setFgMultiplier = bindOptional(
			lookup,
			"mc_dlss_set_fg_multiplier",
			FunctionDescriptor.of(JAVA_INT, JAVA_INT) // num_frames_to_generate
		);
		// Optional like setFgMultiplier: the ABI-probe DLL does not export the multiplier
		// oracle either, while every real build since this symbol exists carries it.
		this.queryFgMultiplier = bindOptional(
			lookup,
			"mc_dlss_query_fg_multiplier",
			FunctionDescriptor.of(JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
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
		this.fillVelocity = bind(
			lookup,
			"mc_dlss_fill_velocity",
			FunctionDescriptor.of(JAVA_INT, ValueLayout.ADDRESS) // const McDlssFillVelocityInfo*
		);
		this.presentOutput = bind(
			lookup,
			"mc_dlss_present_output",
			FunctionDescriptor.of(JAVA_INT, ValueLayout.ADDRESS) // const McDlssPresentInfo*
		);
		this.evaluateSuperResolution = bind(
			lookup,
			"mc_dlss_evaluate",
			FunctionDescriptor.of(JAVA_INT, ValueLayout.ADDRESS) // const McDlssEvaluateInfo*
		);
		this.tagSrResources = bind(
			lookup,
			"mc_dlss_tag_sr_resources",
			FunctionDescriptor.of(JAVA_INT, ValueLayout.ADDRESS) // const McDlssTagInfo*
		);
		// Optional like configureFg: the ABI-probe DLL the layout tests compile stubs the
		// historical ABI surface and does not export the FG tag yet, while every real build
		// since this symbol exists carries it.
		this.tagFgResources = bindOptional(
			lookup,
			"mc_dlss_tag_fg_resources",
			FunctionDescriptor.of(JAVA_INT, ValueLayout.ADDRESS) // const McDlssFgTagInfo*
		);
		// Optional like configureFg: the ABI-probe DLL the layout tests compile stubs the
		// historical ABI surface and does not export the present-handoff record yet, while
		// every real build since this symbol exists carries it.
		this.presentHandoff = bindOptional(
			lookup,
			"mc_dlss_present_handoff",
			FunctionDescriptor.of(JAVA_INT)
		);
		this.presentStart = bindOptional(lookup, "mc_dlss_present_start", FunctionDescriptor.of(JAVA_INT));
		this.presentEnd = bindOptional(lookup, "mc_dlss_present_end", FunctionDescriptor.of(JAVA_INT));
		// Optional like presentHandoff: the ABI-probe DLL the layout tests compile stubs the
		// historical ABI surface and does not export the input-completion wait yet, while
		// every real build since this symbol exists carries it.
		this.waitFgInputsIdle = bindOptional(
			lookup,
			"mc_dlss_wait_fg_inputs_idle",
			FunctionDescriptor.of(JAVA_INT)
		);
		// Optional like waitFgInputsIdle: the ABI-probe DLL does not export the Reflex
		// frame-limit record either, while every real build since this symbol exists carries it.
		this.recordReflexFrameLimit = bindOptional(
			lookup,
			"mc_dlss_record_reflex_frame_limit",
			FunctionDescriptor.of(JAVA_INT, JAVA_INT)
		);
		// Optional like waitFgInputsIdle: the ABI-probe DLL does not export the wait oracle
		// either, while every real build since this symbol exists carries it.
		this.waitFgInputsValue = bindOptional(
			lookup,
			"mc_dlss_wait_fg_inputs_value",
			FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG)
		);
		// Optional like waitFgInputsValue: the ABI-probe DLL does not export the DLSS-G state
		// read either, while every real build since this symbol exists carries it.
		this.queryFgState = bindOptional(
			lookup,
			"mc_dlss_query_fg_state",
			FunctionDescriptor.of(JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
		);
		// Optional like queryFgState: the ABI-probe DLL does not export the camera-constants
		// read either, while every real build since this symbol exists carries it.
		this.queryCameraConstants = bindOptional(
			lookup,
			"mc_dlss_query_camera_constants",
			FunctionDescriptor.of(JAVA_INT, ValueLayout.ADDRESS) // McDlssCameraConstants*
		);
		// Optional like queryCameraConstants: the ABI-probe DLL does not export the FG
		// camera-constants read either, while every real build since this symbol exists
		// carries it.
		this.queryFgCameraConstants = bindOptional(
			lookup,
			"mc_dlss_query_fg_camera_constants",
			FunctionDescriptor.of(JAVA_INT, ValueLayout.ADDRESS) // McDlssCameraConstants*
		);
		// Optional like queryFgCameraConstants: the ABI-probe DLL does not export the FG
		// images read either, while every real build since this symbol exists carries it.
		this.queryFgImages = bindOptional(
			lookup,
			"mc_dlss_query_fg_images",
			FunctionDescriptor.of(
				JAVA_INT,
				ValueLayout.ADDRESS, // McDlssImage* depth
				ValueLayout.ADDRESS, // McDlssImage* hudless
				ValueLayout.ADDRESS, // McDlssImage* ui
				ValueLayout.ADDRESS // McDlssImage* motion
			)
		);
		this.reset = bind(lookup, "mc_dlss_reset", FunctionDescriptor.of(JAVA_INT));
		this.close = bind(lookup, "mc_dlss_close", FunctionDescriptor.of(JAVA_INT));
		this.reprojectionScratch = arena.allocate(JAVA_FLOAT, 16);
		this.evaluateScratch = arena.allocate(EVALUATE_LAYOUT);
		this.motionScratch = arena.allocate(MOTION_LAYOUT);
		this.fillScratch = arena.allocate(FILL_LAYOUT);
		this.presentScratch = arena.allocate(PRESENT_LAYOUT);
		this.tagScratch = arena.allocate(TAG_LAYOUT);
		this.fgTagScratch = arena.allocate(FG_TAG_LAYOUT);
	}

	static Native open(final Path libraryPath) {
		Objects.requireNonNull(libraryPath, "libraryPath");
		final Path absoluteLibrary = libraryPath.toAbsolutePath();
		final Path runtime = absoluteLibrary.getParent();
		if (runtime != null && Files.isRegularFile(runtime.resolve("sl.interposer.dll"))) {
			for (String name : List.of("sl.common.dll", "sl.interposer.dll")) {
				System.load(runtime.resolve(name).toString());
			}
		}
		final Arena arena = Arena.ofShared();
		try {
			// The lookup is shared and pinned, never tied to this bridge's arena: closing the
			// bridge must not unload the module the next bridge's calls run against.
			final SymbolLookup lookup = PINNED_LOOKUPS.computeIfAbsent(
				absoluteLibrary,
				path -> SymbolLookup.libraryLookup(path, Arena.global())
			);
			return new Native(arena, lookup);
		} catch (Throwable error) {
			arena.close();
			throw new NativeException("load-library", error);
		}
	}

	public int bootstrapStreamline(final Path pluginPath) {
		Objects.requireNonNull(pluginPath, "pluginPath");
		if (bootstrapStreamline == null) throw new NativeException("bootstrap-streamline", new IllegalStateException("Native bridge lacks Streamline bootstrap"));
		try (Arena callArena = Arena.ofConfined()) {
			return (int)this.bootstrapStreamline.invokeExact(callArena.allocateFrom(pluginPath.toString()));
		} catch (Throwable error) {
			throw nativeError("bootstrap-streamline", error);
		}
	}

	/**
	 * Hands the live instance / physical device / device and graphics + compute queue layout to
	 * Streamline's manual-hook Vulkan integration (slSetVulkanInfo). Each queue index is the
	 * index at which Streamline's own queues start - the number of queues the host created in
	 * that family. Idempotent natively: repeating the same seven values returns success without
	 * re-calling slSetVulkanInfo.
	 */
	public int activateVulkanProxies(
		final long vkInstance,
		final long vkPhysicalDevice,
		final long vkDevice,
		final int graphicsQueueFamily,
		final int graphicsQueueIndex,
		final int computeQueueFamily,
		final int computeQueueIndex
	) {
		try {
			return (int)this.activateVulkanProxies.invokeExact(
				vkInstance,
				vkPhysicalDevice,
				vkDevice,
				graphicsQueueFamily,
				graphicsQueueIndex,
				computeQueueFamily,
				computeQueueIndex
			);
		} catch (Throwable error) {
			throw nativeError("activate-vulkan-proxies", error);
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
	public List<String> queryDeviceFeatures12() {
		return queryFeatureNames(this.queryDeviceFeature12);
	}

	@Override
	public List<String> queryDeviceFeatures13() {
		return queryFeatureNames(this.queryDeviceFeature13);
	}

	/**
	 * Count-then-name walk over one of the native feature-name queries, the same two-call
	 * pattern as the extension queries: first call with a null name returns the count, then one
	 * call per index copies that name.
	 */
	private List<String> queryFeatureNames(final MethodHandle query) {
		try (Arena callArena = Arena.ofConfined()) {
			final MemorySegment count = callArena.allocate(JAVA_INT);
			int result = (int)query.invokeExact(0, MemorySegment.NULL, 0, count);
			if (result != SUCCESS) {
				throw new NativeException("query-device-features", result);
			}
			final int featureCount = count.get(JAVA_INT, 0);
			final LinkedHashSet<String> names = new LinkedHashSet<>(featureCount);
			for (int index = 0; index < featureCount; index++) {
				final MemorySegment name = callArena.allocate(256);
				result = (int)query.invokeExact(index, name, 256, count);
				if (result != SUCCESS) {
					throw new NativeException("query-device-features", result);
				}
				names.add(name.getString(0));
			}
			return List.copyOf(names);
		} catch (NativeException error) {
			throw error;
		} catch (Throwable error) {
			throw nativeError("query-device-features", error);
		}
	}

	@Override
	public SlQueueRequirements queryQueueRequirements() {
		try (Arena callArena = Arena.ofConfined()) {
			final MemorySegment graphics = callArena.allocate(JAVA_INT);
			final MemorySegment compute = callArena.allocate(JAVA_INT);
			final MemorySegment opticalFlow = callArena.allocate(JAVA_INT);
			final int result = (int)this.queryQueueRequirements.invokeExact(graphics, compute, opticalFlow);
			if (result != SUCCESS) {
				throw new NativeException("query-queue-requirements", result);
			}
			return new SlQueueRequirements(
				graphics.get(JAVA_INT, 0),
				compute.get(JAVA_INT, 0),
				opticalFlow.get(JAVA_INT, 0)
			);
		} catch (NativeException error) {
			throw error;
		} catch (Throwable error) {
			throw nativeError("query-queue-requirements", error);
		}
	}

	@Override
	public TaggedFrameIndexes taggedFrameIndexes() {
		try (Arena callArena = Arena.ofConfined()) {
			final MemorySegment sr = callArena.allocate(JAVA_INT);
			final MemorySegment fg = callArena.allocate(JAVA_INT);
			final int result = (int)this.queryTaggedFrameIndexes.invokeExact(sr, fg);
			if (result != SUCCESS) {
				throw new NativeException("query-tagged-frame-indexes", result);
			}
			return new TaggedFrameIndexes(sr.get(JAVA_INT, 0), fg.get(JAVA_INT, 0));
		} catch (NativeException error) {
			throw error;
		} catch (Throwable error) {
			throw nativeError("query-tagged-frame-indexes", error);
		}
	}

	@Override
	public PresentMarkerEvents presentMarkers() {
		if (queryPresentMarkers == null) throw new NativeException("query-present-markers", new IllegalStateException("Native bridge lacks the present-marker oracle"));
		try (Arena callArena = Arena.ofConfined()) {
			final MemorySegment startCount = callArena.allocate(JAVA_INT);
			final MemorySegment endCount = callArena.allocate(JAVA_INT);
			final MemorySegment eventCount = callArena.allocate(JAVA_INT);
			final MemorySegment events = callArena.allocate((long) PresentMarkerEvents.LOG_CAPACITY * 2 * JAVA_INT.byteSize());
			final int result = (int)this.queryPresentMarkers.invokeExact(
				startCount,
				endCount,
				eventCount,
				events,
				PresentMarkerEvents.LOG_CAPACITY
			);
			if (result != SUCCESS) {
				throw new NativeException("query-present-markers", result);
			}
			final int total = eventCount.get(JAVA_INT, 0);
			final int readable = Math.min(total, PresentMarkerEvents.LOG_CAPACITY);
			final List<PresentMarkerEvent> log = new ArrayList<>(readable);
			for (int i = 0; i < readable; i++) {
				final int type = events.get(JAVA_INT, (long) i * 2 * JAVA_INT.byteSize());
				final int frameIndex = events.get(JAVA_INT, ((long) i * 2 + 1) * JAVA_INT.byteSize());
				log.add(new PresentMarkerEvent(PresentMarkerType.fromNative(type), frameIndex));
			}
			return new PresentMarkerEvents(
				startCount.get(JAVA_INT, 0),
				endCount.get(JAVA_INT, 0),
				total,
				List.copyOf(log)
			);
		} catch (NativeException error) {
			throw error;
		} catch (Throwable error) {
			throw nativeError("query-present-markers", error);
		}
	}

	@Override
	public int installPclWindow(final long hwnd) {
		if (installPclWindow == null) throw new NativeException("install-pcl-window", new IllegalStateException("Native bridge lacks the PCL window hook"));
		try { return (int)installPclWindow.invokeExact(hwnd); } catch (Throwable error) { throw nativeError("install-pcl-window", error); }
	}

	@Override
	public int reflexInputSample() {
		if (reflexInputSample == null) throw new NativeException("reflex-input-sample", new IllegalStateException("Native bridge lacks the reflex input-sample marker"));
		try { return (int)reflexInputSample.invokeExact(); } catch (Throwable error) { throw nativeError("reflex-input-sample", error); }
	}

	@Override
	public int reflexMarker(final NativeApi.ReflexMarkerType type) {
		if (reflexMarker == null) throw new NativeException("reflex-marker", new IllegalStateException("Native bridge lacks the reflex marker entry"));
		final int value = type.getNativeValue();
		try { return (int)reflexMarker.invokeExact(value); } catch (Throwable error) { throw nativeError("reflex-marker", error); }
	}

	@Override
	public NativeApi.ReflexMarkerEvents reflexMarkers() {
		if (queryReflexMarkers == null) throw new NativeException("query-reflex-markers", new IllegalStateException("Native bridge lacks the reflex-marker oracle"));
		try (Arena callArena = Arena.ofConfined()) {
			final MemorySegment typeCounts = callArena.allocate(JAVA_INT, NativeApi.ReflexMarkerEvents.TYPE_COUNT);
			final MemorySegment eventCount = callArena.allocate(JAVA_INT);
			final MemorySegment events = callArena.allocate((long) NativeApi.ReflexMarkerEvents.LOG_CAPACITY * 2 * JAVA_INT.byteSize());
			final int result = (int)this.queryReflexMarkers.invokeExact(
				typeCounts,
				eventCount,
				events,
				NativeApi.ReflexMarkerEvents.LOG_CAPACITY
			);
			if (result != SUCCESS) {
				throw new NativeException("query-reflex-markers", result);
			}
			final int total = eventCount.get(JAVA_INT, 0);
			final int readable = Math.min(total, NativeApi.ReflexMarkerEvents.LOG_CAPACITY);
			final List<NativeApi.ReflexMarkerEvent> log = new ArrayList<>(readable);
			for (int i = 0; i < readable; i++) {
				final int type = events.get(JAVA_INT, (long) i * 2 * JAVA_INT.byteSize());
				final int frameIndex = events.get(JAVA_INT, ((long) i * 2 + 1) * JAVA_INT.byteSize());
				log.add(new NativeApi.ReflexMarkerEvent(NativeApi.ReflexMarkerType.fromNative(type), frameIndex));
			}
			final int[] counts = new int[NativeApi.ReflexMarkerEvents.TYPE_COUNT];
			for (int i = 0; i < counts.length; i++) {
				counts[i] = typeCounts.get(JAVA_INT, (long) i * JAVA_INT.byteSize());
			}
			return new NativeApi.ReflexMarkerEvents(counts, total, List.copyOf(log));
		} catch (NativeException error) {
			throw error;
		} catch (Throwable error) {
			throw nativeError("query-reflex-markers", error);
		}
	}

	@Override
	public NativeApi.ReflexRegistration queryReflexOptions() {
		if (queryReflexOptions == null) throw new NativeException("query-reflex-options", new IllegalStateException("Native bridge lacks the reflex options oracle"));
		try (Arena callArena = Arena.ofConfined()) {
			final MemorySegment mode = callArena.allocate(JAVA_INT);
			final MemorySegment calls = callArena.allocate(JAVA_INT);
			final int result = (int)this.queryReflexOptions.invokeExact(mode, calls);
			if (result != SUCCESS) {
				throw new NativeException("query-reflex-options", result);
			}
			return new NativeApi.ReflexRegistration(mode.get(JAVA_INT, 0), calls.get(JAVA_INT, 0));
		} catch (NativeException error) {
			throw error;
		} catch (Throwable error) {
			throw nativeError("query-reflex-options", error);
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
	public Dimensions queryOptimalDimensions(
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
			return new Dimensions(renderWidth.get(JAVA_INT, 0), renderHeight.get(JAVA_INT, 0));
		} catch (NativeException error) {
			throw error;
		} catch (Throwable error) {
			throw nativeError("query-dimensions", error);
		}
	}

	@Override
	public int configureSuperResolution(
		final int outputWidth,
		final int outputHeight,
		final int renderWidth,
		final int renderHeight,
		final int qualityMode,
		final int renderPreset
	) {
		try {
			return (int)this.configureSuperResolution.invokeExact(
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
	public int configureFg(final int numBackBuffers) {
		if (configureFg == null) throw new NativeException("configure-fg", new IllegalStateException("Native bridge lacks FG configure"));
		try {
			return (int)this.configureFg.invokeExact(numBackBuffers);
		} catch (Throwable error) {
			throw nativeError("configure-fg", error);
		}
	}

	@Override
	public int setFgMode(final int fgEnabled) {
		if (setFgMode == null) throw new NativeException("set-fg-mode", new IllegalStateException("Native bridge lacks the FG mode record"));
		try {
			return (int)this.setFgMode.invokeExact(fgEnabled);
		} catch (Throwable error) {
			throw nativeError("set-fg-mode", error);
		}
	}

	@Override
	public int setFgMultiplier(final int numFramesToGenerate) {
		if (setFgMultiplier == null) throw new NativeException("set-fg-multiplier", new IllegalStateException("Native bridge lacks the FG multiplier record"));
		try {
			return (int)this.setFgMultiplier.invokeExact(numFramesToGenerate);
		} catch (Throwable error) {
			throw nativeError("set-fg-multiplier", error);
		}
	}

	@Override
	public FgMultiplier queryFgMultiplier() {
		if (queryFgMultiplier == null) throw new NativeException("query-fg-multiplier", new IllegalStateException("Native bridge lacks the FG multiplier query"));
		try (Arena callArena = Arena.ofConfined()) {
			final MemorySegment current = callArena.allocate(JAVA_INT);
			final MemorySegment max = callArena.allocate(JAVA_INT);
			final int result = (int)this.queryFgMultiplier.invokeExact(current, max);
			if (result != SUCCESS) {
				throw new NativeException("query-fg-multiplier", result);
			}
			return new FgMultiplier(
				current.get(JAVA_INT, 0),
				max.get(JAVA_INT, 0)
			);
		} catch (NativeException error) {
			throw error;
		} catch (Throwable error) {
			throw nativeError("query-fg-multiplier", error);
		}
	}

	@Override
	public EvaluationImages acquireImages() {
		try (Arena callArena = Arena.ofConfined()) {
			final MemorySegment motion = callArena.allocate(IMAGE_LAYOUT);
			final MemorySegment output = callArena.allocate(IMAGE_LAYOUT);
			final int result = (int)this.acquireImages.invokeExact(motion, output);
			if (result != SUCCESS) {
				throw new NativeException("acquire-images", result);
			}
			return new EvaluationImages(readImage(motion), readImage(output));
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
		view.set(target, 0L, binding.view());
		image.set(target, 0L, binding.image());
		format.set(target, 0L, binding.format());
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
	public FrameTimings frameTimings() {
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
			return new FrameTimings(
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
		final float[] reprojection = request.reprojection();
		if (reprojection.length != 16) {
			throw new IllegalArgumentException("Reprojection must be 16 column-major floats");
		}
		final Dimensions render = requireDimensions(request.renderDimensions(), "write-motion");
		try {
			final MemorySegment matrix = this.reprojectionScratch;
			MemorySegment.copy(reprojection, 0, matrix, JAVA_FLOAT, 0, reprojection.length);
			final MemorySegment info = this.motionScratch;
			MOTION_COMMAND_BUFFER.set(info, 0L, request.commandBuffer());
			writeImage(info, MOTION_DEPTH_VIEW, MOTION_DEPTH_IMAGE, MOTION_DEPTH_FORMAT, request.depth());
			MOTION_REPROJECTION.set(info, 0L, matrix);
			MOTION_RENDER_WIDTH.set(info, 0L, render.width());
			MOTION_RENDER_HEIGHT.set(info, 0L, render.height());
			return (int)this.writeMotion.invokeExact(info);
		} catch (Throwable error) {
			throw nativeError("write-motion", error);
		}
	}

	@Override
	public int fillVelocity(final FillVelocityRequest request) {
		final float[] reprojection = request.reprojection();
		if (reprojection.length != 16) {
			throw new IllegalArgumentException("Reprojection must be 16 column-major floats");
		}
		final Dimensions render = requireDimensions(request.renderDimensions(), "fill-velocity");
		try {
			final MemorySegment matrix = this.reprojectionScratch;
			MemorySegment.copy(reprojection, 0, matrix, JAVA_FLOAT, 0, reprojection.length);
			final MemorySegment info = this.fillScratch;
			FILL_COMMAND_BUFFER.set(info, 0L, request.commandBuffer());
			writeImage(info, FILL_DEPTH_VIEW, FILL_DEPTH_IMAGE, FILL_DEPTH_FORMAT, request.depth());
			writeImage(info, FILL_VELOCITY_VIEW, FILL_VELOCITY_IMAGE, FILL_VELOCITY_FORMAT, request.velocity());
			FILL_REPROJECTION.set(info, 0L, matrix);
			FILL_RENDER_WIDTH.set(info, 0L, render.width());
			FILL_RENDER_HEIGHT.set(info, 0L, render.height());
			FILL_RESET.set(info, 0L, request.reset() ? 1 : 0);
			return (int)this.fillVelocity.invokeExact(info);
		} catch (Throwable error) {
			throw nativeError("fill-velocity", error);
		}
	}

	@Override
	public int presentStart() {
		if (presentStart == null) throw new NativeException("present-start", new IllegalStateException("Native bridge lacks present start"));
		try { return (int)presentStart.invokeExact(); } catch (Throwable error) { throw nativeError("present-start", error); }
	}

	@Override
	public int presentEnd() {
		if (presentEnd == null) throw new NativeException("present-end", new IllegalStateException("Native bridge lacks present end"));
		try { return (int)presentEnd.invokeExact(); } catch (Throwable error) { throw nativeError("present-end", error); }
	}

	@Override
	public int presentOutput(final PresentTarget target) {
		final Dimensions output = requireDimensions(target.outputDimensions(), "present-output");
		try {
			final MemorySegment info = this.presentScratch;
			PRESENT_COMMAND_BUFFER.set(info, 0L, target.commandBuffer());
			PRESENT_IMAGE.set(info, 0L, target.image());
			PRESENT_WIDTH.set(info, 0L, output.width());
			PRESENT_HEIGHT.set(info, 0L, output.height());
			return (int)this.presentOutput.invokeExact(info);
		} catch (Throwable error) {
			throw nativeError("present-output", error);
		}
	}

	@Override
	public int evaluateSuperResolution(final EvaluationRequest request) {
		final Dimensions render = requireDimensions(request.renderDimensions(), "evaluate");
		// The camera's six arrays are fixed-length fields of the ABI struct: an array of any
		// other length would either read past its field or leave the field's tail holding the
		// previous frame's floats through the reused scratch - a partially-written camera no
		// diagnostic would catch. The check runs before any byte of the scratch is written,
		// so a refused camera never half-corrupts the struct a later valid call reads.
		final CameraConstants camera = request.camera();
		if (camera != null) {
			requireCameraLength(camera.viewToClip(), 16, "viewToClip");
			requireCameraLength(camera.clipToView(), 16, "clipToView");
			requireCameraLength(camera.clipToPrevClip(), 16, "clipToPrevClip");
			requireCameraLength(camera.prevClipToClip(), 16, "prevClipToClip");
			requireCameraLength(camera.pos(), 3, "pos");
			requireCameraLength(camera.right(), 3, "right");
			requireCameraLength(camera.up(), 3, "up");
			requireCameraLength(camera.fwd(), 3, "fwd");
		}
		try {
			final MemorySegment info = this.evaluateScratch;
			EVALUATE_COMMAND_BUFFER.set(info, 0L, request.commandBuffer());
			writeImage(info, EVALUATE_COLOR_VIEW, EVALUATE_COLOR_IMAGE, EVALUATE_COLOR_FORMAT, request.color());
			writeImage(info, EVALUATE_DEPTH_VIEW, EVALUATE_DEPTH_IMAGE, EVALUATE_DEPTH_FORMAT, request.depth());
			EVALUATE_JITTER_X.set(info, 0L, request.jitter().x());
			EVALUATE_JITTER_Y.set(info, 0L, request.jitter().y());
			EVALUATE_MOTION_SCALE_X.set(info, 0L, request.motionScale().x());
			EVALUATE_MOTION_SCALE_Y.set(info, 0L, request.motionScale().y());
			EVALUATE_RENDER_WIDTH.set(info, 0L, render.width());
			EVALUATE_RENDER_HEIGHT.set(info, 0L, render.height());
			EVALUATE_FRAME_TIME.set(info, 0L, request.frameTimeMilliseconds());
			EVALUATE_RESET_HISTORY.set(info, 0L, request.resetHistory() ? 1 : 0);
			// The frame's camera travels in the same struct so the evaluation's single
			// slSetConstants records it together with the jitter and reset flag under the frame's
			// retained token. The scratch is reused across calls, so a null camera zeroes the
			// region rather than leaking the previous frame's camera into this one.
			if (camera == null) {
				info.asSlice(EVALUATE_CAMERA_OFFSET, CAMERA_LAYOUT.byteSize()).fill((byte)0);
			} else {
				writeCameraFloats(info, evaluateCameraFieldOffset("view_to_clip"), camera.viewToClip());
				writeCameraFloats(info, evaluateCameraFieldOffset("clip_to_view"), camera.clipToView());
				writeCameraFloats(info, evaluateCameraFieldOffset("clip_to_prev_clip"), camera.clipToPrevClip());
				writeCameraFloats(info, evaluateCameraFieldOffset("prev_clip_to_clip"), camera.prevClipToClip());
				writeCameraFloats(info, evaluateCameraFieldOffset("pos"), camera.pos());
				writeCameraFloats(info, evaluateCameraFieldOffset("right"), camera.right());
				writeCameraFloats(info, evaluateCameraFieldOffset("up"), camera.up());
				writeCameraFloats(info, evaluateCameraFieldOffset("fwd"), camera.fwd());
				info.set(JAVA_FLOAT, evaluateCameraScalarOffset("near_plane"), camera.near());
				info.set(JAVA_FLOAT, evaluateCameraScalarOffset("far_plane"), camera.far());
				info.set(JAVA_FLOAT, evaluateCameraScalarOffset("fov_radians"), camera.fovRadians());
				info.set(JAVA_FLOAT, evaluateCameraScalarOffset("aspect_ratio"), camera.aspectRatio());
			}
			return (int)this.evaluateSuperResolution.invokeExact(info);
		} catch (Throwable error) {
			throw nativeError("evaluate", error);
		}
	}

	/**
	 * One camera array must be exactly its ABI field's length: shorter would leave the
	 * field's tail holding stale floats from the reused scratch, longer would write past
	 * the field into the next one.
	 */
	private static void requireCameraLength(final float[] values, final int expected, final String field) {
		if (values == null || values.length != expected) {
			throw new IllegalArgumentException("Camera " + field + " must be exactly " + expected + " floats");
		}
	}

	@Override
	public int tagSrResources(final SrTagRequest request) {
		try {
			final MemorySegment info = this.tagScratch;
			TAG_COMMAND_BUFFER.set(info, 0L, request.commandBuffer());
			writeImage(info, TAG_COLOR_VIEW, TAG_COLOR_IMAGE, TAG_COLOR_FORMAT, request.color());
			writeImage(info, TAG_DEPTH_VIEW, TAG_DEPTH_IMAGE, TAG_DEPTH_FORMAT, request.depth());
			return (int)this.tagSrResources.invokeExact(info);
		} catch (Throwable error) {
			throw nativeError("tag-sr-resources", error);
		}
	}

	@Override
	public int tagFrameGenerationResources(final FgTagRequest request) {
		if (tagFgResources == null) throw new NativeException("tag-fg-resources", new IllegalStateException("Native bridge lacks FG tag"));
		try {
			final MemorySegment info = this.fgTagScratch;
			FG_TAG_COMMAND_BUFFER.set(info, 0L, request.commandBuffer());
			writeImage(info, FG_TAG_DEPTH_VIEW, FG_TAG_DEPTH_IMAGE, FG_TAG_DEPTH_FORMAT, request.depth());
			writeImage(info, FG_TAG_HUDLESS_VIEW, FG_TAG_HUDLESS_IMAGE, FG_TAG_HUDLESS_FORMAT, request.hudless());
			writeImage(info, FG_TAG_UI_VIEW, FG_TAG_UI_IMAGE, FG_TAG_UI_FORMAT, request.ui());
			return (int)this.tagFgResources.invokeExact(info);
		} catch (Throwable error) {
			throw nativeError("tag-fg-resources", error);
		}
	}

	/**
	 * The dimensions the adapter stamps onto a request are what the bridge checks its caller
	 * against, so a request that reached here without them is a wiring mistake rather than a
	 * native failure - it would otherwise be sent as a zero the bridge rejects for the wrong
	 * reason.
	 */
	private static Dimensions requireDimensions(final Dimensions dimensions, final String stage) {
		if (dimensions == null) {
			throw new IllegalStateException(stage + " requires dimensions stamped by the adapter");
		}
		return dimensions;
	}

	@Override
	public int recordPresentHandoff() {
		if (presentHandoff == null) throw new NativeException("present-handoff", new IllegalStateException("Native bridge lacks present handoff"));
		try {
			return (int)this.presentHandoff.invokeExact();
		} catch (Throwable error) {
			throw nativeError("present-handoff", error);
		}
	}

	@Override
	public int waitFgInputsIdle() {
		if (waitFgInputsIdle == null) throw new NativeException("wait-fg-inputs", new IllegalStateException("Native bridge lacks FG input wait"));
		try {
			return (int)this.waitFgInputsIdle.invokeExact();
		} catch (Throwable error) {
			throw nativeError("wait-fg-inputs", error);
		}
	}

	@Override
	public int recordReflexFrameLimit(int frameLimitUs) {
		if (recordReflexFrameLimit == null) throw new NativeException("record-reflex-frame-limit", new IllegalStateException("Native bridge lacks the Reflex frame-limit record"));
		try {
			return (int)this.recordReflexFrameLimit.invokeExact(frameLimitUs);
		} catch (Throwable error) {
			throw nativeError("record-reflex-frame-limit", error);
		}
	}

	@Override
	public int waitFgInputsValue(long vkDevice, long semaphore, long value) {
		if (waitFgInputsValue == null) throw new NativeException("wait-fg-inputs-value", new IllegalStateException("Native bridge lacks the FG input value wait oracle"));
		try {
			return (int)this.waitFgInputsValue.invokeExact(vkDevice, semaphore, value);
		} catch (Throwable error) {
			throw nativeError("wait-fg-inputs-value", error);
		}
	}

	@Override
	public FgState queryFgState() {
		if (queryFgState == null) throw new NativeException("query-fg-state", new IllegalStateException("Native bridge lacks the FG state query"));
		try (Arena callArena = Arena.ofConfined()) {
			final MemorySegment status = callArena.allocate(JAVA_INT);
			final MemorySegment numFramesPresented = callArena.allocate(JAVA_INT);
			final MemorySegment fenceValue = callArena.allocate(JAVA_LONG);
			final MemorySegment fence = callArena.allocate(JAVA_LONG);
			final int result = (int)this.queryFgState.invokeExact(status, numFramesPresented, fenceValue, fence);
			if (result != SUCCESS) {
				throw new NativeException("query-fg-state", result);
			}
			return new FgState(
				status.get(JAVA_INT, 0),
				numFramesPresented.get(JAVA_INT, 0),
				fenceValue.get(JAVA_LONG, 0),
				fence.get(JAVA_LONG, 0)
			);
		} catch (NativeException error) {
			throw error;
		} catch (Throwable error) {
			throw nativeError("query-fg-state", error);
		}
	}

	@Override
	public CameraConstants queryCameraConstants() {
		if (queryCameraConstants == null) {
			throw new NativeException("query-camera-constants", new IllegalStateException("Native bridge lacks the camera-constants query"));
		}
		try (Arena callArena = Arena.ofConfined()) {
			final MemorySegment out = callArena.allocate(CAMERA_LAYOUT);
			final int result = (int)this.queryCameraConstants.invokeExact(out);
			if (result != SUCCESS) {
				throw new NativeException("query-camera-constants", result);
			}
			return readCameraConstants(out);
		} catch (NativeException error) {
			throw error;
		} catch (Throwable error) {
			throw nativeError("query-camera-constants", error);
		}
	}

	@Override
	public CameraConstants queryFgCameraConstants() {
		if (queryFgCameraConstants == null) {
			throw new NativeException("query-fg-camera-constants", new IllegalStateException("Native bridge lacks the FG camera-constants query"));
		}
		try (Arena callArena = Arena.ofConfined()) {
			final MemorySegment out = callArena.allocate(CAMERA_LAYOUT);
			final int result = (int)this.queryFgCameraConstants.invokeExact(out);
			if (result != SUCCESS) {
				throw new NativeException("query-fg-camera-constants", result);
			}
			return readCameraConstants(out);
		} catch (NativeException error) {
			throw error;
		} catch (Throwable error) {
			throw nativeError("query-fg-camera-constants", error);
		}
	}

	@Override
	public FgOrientationImages queryFgImages() {
		if (queryFgImages == null) {
			throw new NativeException("query-fg-images", new IllegalStateException("Native bridge lacks the FG images query"));
		}
		try (Arena callArena = Arena.ofConfined()) {
			final MemorySegment depth = callArena.allocate(IMAGE_LAYOUT);
			final MemorySegment hudless = callArena.allocate(IMAGE_LAYOUT);
			final MemorySegment ui = callArena.allocate(IMAGE_LAYOUT);
			final MemorySegment motion = callArena.allocate(IMAGE_LAYOUT);
			final int result = (int)this.queryFgImages.invokeExact(depth, hudless, ui, motion);
			if (result != SUCCESS) {
				throw new NativeException("query-fg-images", result);
			}
			return new FgOrientationImages(
				readImage(depth), readImage(hudless), readImage(ui), readImage(motion)
			);
		} catch (NativeException error) {
			throw error;
		} catch (Throwable error) {
			throw nativeError("query-fg-images", error);
		}
	}

	/** Reads one McDlssCameraConstants segment back into the bridge type. */
	private static CameraConstants readCameraConstants(final MemorySegment out) {
		return new CameraConstants(
			readCameraFloats(out, 0, 16),
			readCameraFloats(out, cameraFloatOffset("clip_to_view", 0), 16),
			readCameraFloats(out, cameraFloatOffset("pos", 0), 3),
			readCameraFloats(out, cameraFloatOffset("right", 0), 3),
			readCameraFloats(out, cameraFloatOffset("up", 0), 3),
			readCameraFloats(out, cameraFloatOffset("fwd", 0), 3),
			readCameraFloats(out, cameraFloatOffset("clip_to_prev_clip", 0), 16),
			readCameraFloats(out, cameraFloatOffset("prev_clip_to_clip", 0), 16),
			out.get(JAVA_FLOAT, CAMERA_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("near_plane"))),
			out.get(JAVA_FLOAT, CAMERA_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("far_plane"))),
			out.get(JAVA_FLOAT, CAMERA_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("fov_radians"))),
			out.get(JAVA_FLOAT, CAMERA_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("aspect_ratio"))),
			out.get(JAVA_FLOAT, CAMERA_LAYOUT.byteOffset(
				MemoryLayout.PathElement.groupElement("jitter"),
				MemoryLayout.PathElement.groupElement("x")
			)),
			out.get(JAVA_FLOAT, CAMERA_LAYOUT.byteOffset(
				MemoryLayout.PathElement.groupElement("jitter"),
				MemoryLayout.PathElement.groupElement("y")
			))
		);
	}

	/** Copies one camera field's floats into the evaluate struct at [base]. */
	private static void writeCameraFloats(final MemorySegment segment, final long base, final float[] values) {
		for (int i = 0; i < values.length; i++) {
			segment.set(JAVA_FLOAT, base + i * 4L, values[i]);
		}
	}

	/** Reads [count] floats starting at [base] out of a camera struct segment. */
	private static float[] readCameraFloats(final MemorySegment segment, final long base, final int count) {
		final float[] values = new float[count];
		for (int i = 0; i < count; i++) {
			values[i] = segment.get(JAVA_FLOAT, base + i * 4L);
		}
		return values;
	}

	public int resetSuperResolutionHistory() {
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
			this.nativeArena.close();
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

	private static MethodHandle bindOptional(
		final SymbolLookup lookup,
		final String symbol,
		final FunctionDescriptor descriptor
	) {
		return lookup.find(symbol).map(segment -> LINKER.downcallHandle(segment, descriptor)).orElse(null);
	}

	private static NativeException nativeError(final String stage, final Throwable error) {
		if (error instanceof NativeException nativeError) {
			return nativeError;
		}
		return new NativeException(stage, error);
	}
}
