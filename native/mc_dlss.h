#ifndef MC_DLSS_H
#define MC_DLSS_H

#include <stdint.h>

#if defined(_WIN32)
#define MC_DLSS_API __declspec(dllexport)
#define MC_DLSS_CALL __cdecl
#else
#define MC_DLSS_API __attribute__((visibility("default")))
#define MC_DLSS_CALL
#endif

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Flat ABI used by Java 25 FFM. NVIDIA NGX headers remain reference-only vocabulary: the
 * quality-mode, preset, and result values the ABI speaks in keep their NGX names, and no
 * direct-NGX runtime call exists behind them. Streamline owns every feature path.
 *
 * Every function returns 1 on success and an NGX-valued result code otherwise. The bridge
 * owns its images and motion pass; the Streamline runtime is process-wide and outlives the
 * bridge.
 *
 * The per-frame recording calls take a pointer to a description struct rather than a
 * long argument list. The struct is what makes the call self-describing: the flat form
 * carried four view/image/format triples and four dimensions positionally, where two
 * adjacent handles of the same width could be transposed by either side without any
 * diagnostic. Passing by pointer also lets a field be appended without breaking the
 * layout callers already agree on.
 */

/*
 * One image handed across the ABI: its view, its image, and its format.
 *
 * The subresource range is deliberately absent. Every image Minecraft's Vulkan backend
 * creates is a single-level, single-layer 2D image, and the module's own images are
 * full-range, so the range is derived natively - and the aspect with it, from the role the
 * image plays in the call that carries it. The ABI used to carry all five range fields per
 * image and every producer sent the same values.
 *
 * Layout is 20 bytes of fields in 24 bytes of struct: the trailing padding is what keeps
 * `format` 8-byte aligned inside arrays and enclosing structs, and any binding on the other
 * side of the ABI has to declare it explicitly rather than infer it.
 */
typedef struct McDlssImage {
    uint64_t view;
    uint64_t image;
    uint32_t format;
} McDlssImage;

/* A two-component float pair: a jitter offset or a motion-vector scale. */
typedef struct McDlssVec2 {
    float x;
    float y;
} McDlssVec2;

/*
 * Bootstraps the Streamline runtime (slInit) against the plugin directory `plugin_path`, with
 * the manual-hook Vulkan integration and frame-based resource tagging enabled.
 *
 * Must be called before the Vulkan instance and device are created: the extension, feature,
 * and queue queries below require it. After the device exists,
 * mc_dlss_activate_vulkan_proxies records the live handles with slSetVulkanInfo, which is
 * what the DLSS options, queries, and tagging calls require.
 *
 * `plugin_path` is the directory holding sl.interposer.dll and the feature plugins it loads.
 * Idempotent: slInit's two errors for "the runtime is already up" (a previous bridge loaded
 * it in this process) are treated as success.
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_bootstrap_streamline(const char* plugin_path);

/*
 * Activates Streamline's manual-hook Vulkan proxies against the caller's live device.
 *
 * Must be called after mc_dlss_bootstrap_streamline and after the Vulkan device exists, when
 * the application creates the instance/device itself instead of going through Streamline's
 * vkCreateInstance/vkCreateDevice proxies. Carries the live handles and the graphics + compute
 * queue layout to slSetVulkanInfo, which is what routes the application's Vulkan loading -
 * already redirected to sl.interposer.dll by the mod entrypoint - through Streamline's
 * present/acquire/swapchain hooks.
 *
 * `graphics_queue_family`/`compute_queue_family` are the queue families the host's graphics
 * and compute queues were created in, and `graphics_queue_index`/`compute_queue_index` are the
 * indices at which Streamline's own queues start: the number of queues the host created in
 * each family. Streamline adds its required queues after the host's, so the host hands over
 * its queue COUNTS, not handle indices. No optical-flow family is passed: without a host
 * optical-flow family, DLSS-G runs optical flow in interop mode.
 *
 * Idempotent: repeating the same seven values returns success without re-calling
 * slSetVulkanInfo. A different device than the one already recorded cannot replace live
 * Streamline ownership without a shutdown, so that returns kInvalidParameter.
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_activate_vulkan_proxies(
    uint64_t vk_instance,
    uint64_t vk_physical_device,
    uint64_t vk_device,
    uint32_t graphics_queue_family,
    uint32_t graphics_queue_index,
    uint32_t compute_queue_family,
    uint32_t compute_queue_index);

/*
 * Streamline feature requirements, queried before the Vulkan instance and device are created.
 *
 * Returns the deduplicated Vulkan instance (mc_dlss_query_instance_extension) or device
 * (mc_dlss_query_device_extension) extension names the loaded features require, drawn from
 * slGetFeatureRequirements across every enabled feature. Must be called after
 * mc_dlss_bootstrap_streamline: the retired direct-NGX discovery fallback no longer answers
 * a query that ran before bootstrap.
 *
 * Two-call shape: first call with index==0 && name==NULL && name_capacity==0 returns the
 * count in *extension_count; subsequent calls with a valid name buffer copy the i-th
 * extension name (NUL terminated) and return the count. Returns an NGX/native result code on
 * failure (e.g. FAIL_InvalidParameter).
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_instance_extension(
    uint32_t index,
    char* name,
    uint32_t name_capacity,
    uint32_t* extension_count);

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_device_extension(
    uint64_t vk_instance,
    uint64_t vk_physical_device,
    uint32_t index,
    char* name,
    uint32_t name_capacity,
    uint32_t* extension_count);

/*
 * Pre-creation Streamline device-feature requirements.
 *
 * Must be called after mc_dlss_bootstrap_streamline and before the Vulkan device is created.
 * Returns the deduplicated Vulkan 1.2 (mc_dlss_query_device_feature_12) or Vulkan 1.3
 * (mc_dlss_query_device_feature_13) feature names the loaded features require, drawn from
 * slGetFeatureRequirements' vkFeatures12/vkFeatures13 across every enabled feature.
 *
 * Same two-call shape as the extension queries: first call with index==0 && name==NULL &&
 * name_capacity==0 returns the count in *feature_count; subsequent calls with a valid name
 * buffer copy the i-th name (NUL terminated) and return the count.
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_device_feature_12(
    uint32_t index,
    char* name,
    uint32_t name_capacity,
    uint32_t* feature_count);

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_device_feature_13(
    uint32_t index,
    char* name,
    uint32_t name_capacity,
    uint32_t* feature_count);

/*
 * Pre-creation Streamline queue requirements.
 *
 * Must be called after mc_dlss_bootstrap_streamline and before the Vulkan device is created.
 * Returns the summed extra graphics / compute / optical-flow queue counts the loaded features
 * require the host to create, from slGetFeatureRequirements' vkNumGraphicsQueuesRequired /
 * vkNumComputeQueuesRequired / vkNumOpticalFlowQueuesRequired across every enabled feature.
 *
 * The graphics and compute counts are merged into the host's queue-family create map before
 * vkCreateDevice. The optical-flow count is reported but the host is not expected to create
 * the family: in its absence DLSS-G runs optical flow in interop mode.
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_queue_requirements(
    uint32_t* extra_graphics_queues,
    uint32_t* extra_compute_queues,
    uint32_t* extra_optical_flow_queues);

/*
 * The Streamline frame indices the last mc_dlss_tag_sr_resources and
 * mc_dlss_tag_fg_resources calls tagged under, as the runtime numbered them.
 *
 * One frame's SR and FG tags must land under the same index: the FG tag reuses the frame
 * token the SR tag obtained and retained (slGetNewFrameToken is not called again while a
 * token is retained), and equality of the two records is the oracle that proves it. A tag
 * that advanced the frame instead would record a strictly later index under the FG slot.
 *
 * Both pointers are written on success. Answers not-initialized until both tag calls have
 * recorded at least once, because equality of two never-recorded slots is meaningless.
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_tagged_frame_indexes(
    uint32_t* sr_frame_index,
    uint32_t* fg_frame_index);

/*
 * Validates and records the live Vulkan tuple the module's own images and motion pass are
 * allocated against, and nothing else: the retired direct-NGX initialization no longer runs
 * behind it. Must be called after mc_dlss_bootstrap_streamline and
 * mc_dlss_activate_vulkan_proxies with the same handles that were handed to slSetVulkanInfo.
 * An initialize that ran before bootstrap or proxy activation - or with a tuple that
 * disagrees with the activated one - fails and records nothing.
 *
 * `sdk_path` and `data_path` are compatibility inputs: the retired direct-NGX path used them
 * to locate its feature DLL and data, and nothing in the Streamline stack consumes them. They
 * are validated as well-formed paths and otherwise ignored.
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_initialize(
    uint64_t vk_instance,
    uint64_t vk_physical_device,
    uint64_t vk_device,
    const char* sdk_path,
    const char* data_path);

/*
 * Validates and records the live Vulkan tuple the module-owned images and motion pass are
 * allocated against.
 *
 * Must be called after mc_dlss_bootstrap_streamline and mc_dlss_activate_vulkan_proxies with
 * the same instance / physical device / device that was handed to slSetVulkanInfo: a tuple
 * that disagrees with the recorded proxy tuple is refused. Repeating the same three handles
 * returns success without re-recording; a different tuple cannot replace the recorded one
 * without close.
 *
 * `sdk_path` and `data_path` are compatibility inputs. The retired direct-NGX implementation
 * used them to locate its feature DLL and data; nothing in the Streamline stack consumes
 * them, so they are validated as well-formed paths and otherwise ignored.
 *
 * The first successful transition also records the Reflex options registration the pinned
 * Reflex guide requires: one slReflexSetOptions call with sl::ReflexMode::eLowLatency. A
 * failed registration does not fail the transition - the marker surface never latches the
 * session, and Reflex registration must not gate the SR/FG session either - the oracle
 * mc_dlss_query_reflex_options reports whether it succeeded. Repeating the recorded tuple
 * re-records neither the tuple nor the Reflex options.
 */

/*
 * Queries the DLSS optimal render dimensions for an output size and quality mode, answered by
 * Streamline's slDLSSGetOptimalSettings.
 *
 * Must be called after mc_dlss_bootstrap_streamline and mc_dlss_activate_vulkan_proxies: the
 * query needs the DLSS plugin loaded and the Vulkan device recorded. DLAA is anti-aliasing at
 * native resolution, so it returns the output dimensions without querying.
 *
 * `quality_mode` is an NVSDK_NGX_PerfQuality_Value (MaxPerf 0, Balanced 1, MaxQuality 2,
 * UltraPerformance 3, DLAA 5); the bridge maps it onto the sl::DLSSMode of the same rank.
 * Any other value is refused.
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_optimal_dimensions(
    uint32_t output_width,
    uint32_t output_height,
    uint32_t quality_mode,
    uint32_t* render_width,
    uint32_t* render_height);

/*
 * Stores the dimensions, the NGX-valued performance/quality mode, and the DLSS render preset
 * the SR configuration uses, and records them with Streamline's slDLSSSetOptions.
 *
 * Must be called after mc_dlss_bootstrap_streamline and mc_dlss_activate_vulkan_proxies;
 * otherwise returns FAIL_NotInitialized. Owns no command buffer and creates no feature.
 *
 * `quality_mode` is an NVSDK_NGX_PerfQuality_Value: MaxPerf, Balanced, MaxQuality,
 * UltraPerformance, or DLAA. UltraQuality is defined by NGX and not implemented by it, so it
 * is rejected here rather than passed through. The bridge maps the mode onto sl::DLSSMode and
 * writes `render_preset` (an NVSDK_NGX_DLSS_Hint_Render_Preset: J, K, L, or M) onto the
 * preset field sl::DLSSOptions carries for that mode; every other preset field stays the SDK
 * default.
 *
 * These dimensions are the single source of truth for everything sized from them: the images
 * mc_dlss_acquire_images allocates, the tags mc_dlss_tag_sr_resources records, and the sizes
 * the recording calls check their callers against.
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_configure(
    uint32_t output_width,
    uint32_t output_height,
    uint32_t render_width,
    uint32_t render_height,
    uint32_t quality_mode,
    uint32_t render_preset);

/*
 * Records the DLSS-G per-frame options with Streamline's slDLSSGSetOptions.
 *
 * Must be called after mc_dlss_bootstrap_streamline and mc_dlss_activate_vulkan_proxies and
 * after a successful mc_dlss_configure: the record answers FAIL_NotInitialized without a
 * ready Streamline session and FAIL_InvalidParameter while the stored configuration still
 * holds zero dimensions. The call owns no command buffer, tags nothing, and creates no
 * feature - it only records what the next present applies.
 *
 * The record's multiplier is the stored one: numFramesToGenerate = 1 (2x) from the
 * default record, up to the device ceiling after mc_dlss_set_fg_multiplier. The
 * retained-resources flag, the UI-recomposition switch,
 * and the queue-parallelism mode are recorded explicitly rather than inherited from the SDK
 * defaults, and the render/output extents and the five formats come from the stored
 * configuration: the backbuffer, HUD-less, and UI buffers at output size in RGBA8_UNORM, the
 * motion image at render size in R16G16_SFLOAT, and the depth at render size in D32_SFLOAT.
 *
 * `num_back_buffers` is the swapchain's expected image count, declared as the app knows it;
 * adequacy against Streamline's requirement is verified live later in the milestone.
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_configure_fg(uint32_t num_back_buffers);

/*
 * Switches the recorded DLSS-G options' mode through slDLSSGSetOptions: eOn when
 * `fg_enabled` is non-zero, eOff when it is zero.
 *
 * The mode record is the status-latch fallback's native half: after the per-frame
 * mc_dlss_query_fg_state poll reports a status other than eDLSSGStatusOk while FG is
 * active, the session re-records the options in the eOff mode so the plugin stops
 * interpolating, with the retained-resources flag keeping its allocations alive and the
 * same back-buffer count and render/output extents the validated eOn record stored - the
 * record's shape is identical apart from the mode. Answers FAIL_NotInitialized without a
 * ready Streamline session and FAIL_InvalidParameter while no DLSS-G options record is
 * stored (the same gates as the FG tag): the mode record switches an existing record, it
 * never creates one. The re-arm refusal that keeps eOn from coming back for the session is
 * the Kotlin policy's, not this record's.
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_set_fg_mode(uint32_t fg_enabled);

/*
 * Records the DLSS-G frame multiplier through slDLSSGSetOptions:
 * `num_frames_to_generate` generated frames per rendered one (1 = 2x, 2 = 3x, and so on).
 * The value must lie between 1 and the device's DLSSGState::numFramesToGenerateMax, read
 * fresh from slDLSSGGetState, so an unsupported multiplier is refused rather than recorded
 * into options the plugin would silently misread.
 *
 * The record keeps the validated eOn record's shape - mode, retained resources, back-buffer
 * count, render/output extents, formats - and changes only numFramesToGenerate. Answers
 * FAIL_NotInitialized without a ready Streamline session and FAIL_InvalidParameter while no
 * DLSS-G options record is stored (the same gates as the mode record) or the value is
 * outside 1..max. A refused record changes nothing: the stored multiplier and the plugin's
 * options stay exactly as they were.
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_set_fg_multiplier(uint32_t num_frames_to_generate);

/*
 * The multiplier oracle: the numFramesToGenerate the recorded DLSS-G options carry and the
 * device's DLSSGState::numFramesToGenerateMax, read fresh from slDLSSGGetState. The cycle
 * the F12 key drives wraps against the max so an unsupported multiplier is never offered.
 *
 * Answers FAIL_NotInitialized while the Streamline session is not ready and
 * FAIL_InvalidParameter while the DLSS-G options have not recorded (the same gates as
 * mc_dlss_query_fg_state) or either output pointer is null. The read performs no GPU work
 * and never blocks.
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_fg_multiplier(uint32_t* current,
                                                              uint32_t* max);

/*
 * Native-owned evaluation images.
 *
 * DLSS writes its upscaled result into an image the engine does not own, and
 * reads camera motion from one the engine has to fill. Both are allocated here,
 * from the dimensions the last mc_dlss_configure stored: the motion image at
 * render size and the output image at output size, each storage-capable, backed
 * by device-local memory, and carrying a full colour image view.
 *
 * Acquiring twice against unchanged configuration returns the same handles.
 * A configuration change destroys and recreates them. Partial failure leaves
 * nothing allocated, and mc_dlss_reset and mc_dlss_close release them before
 * the Vulkan device they belong to is destroyed.
 *
 * These handles are reported so the caller can see what it is rendering through; they are
 * not carried back in for the evaluation, which reaches its own images directly.
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_acquire_images(
    McDlssImage* motion,
    McDlssImage* output);

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_release_images(void);

/*
 * Blocks until the Vulkan device has finished everything submitted to it.
 *
 * The native side already stalls before its own destroys, but the engine's
 * resources are destroyed by the engine: a render target released because the
 * DLSS configuration changed is freed by Minecraft while the frames that drew
 * into it can still be in flight, and the device is lost several frames later
 * in an unrelated wait. The caller stalls through here before releasing
 * anything the recorded frames referenced.
 *
 * Succeeds when no device has been captured yet, because a session with no
 * device has nothing in flight to wait for.
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_wait_device_idle(void);

/*
 * GPU milliseconds the last completed frame spent in each recorded stage.
 *
 * Measured with device timestamps around the motion pass, the DLSS evaluation, and the copy
 * into the engine target, so the three are separable - frame rate and GPU utilization are
 * not, and on a CPU-bound client neither one moves when this chain gets cheaper or dearer.
 *
 * The result is several frames old and never waits on the GPU. Returns not-initialized until
 * one frame has completed all three stages, and on a device whose queues cannot timestamp
 * graphics work, where the measurement is silently unavailable rather than fatal.
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_frame_timings(
    float* motion_ms,
    float* evaluate_ms,
    float* present_ms,
    float* total_ms);

/*
 * Camera-only motion vectors, recorded on the caller's command buffer.
 *
 * DLSS reads motion from an image nothing in Minecraft fills, so the bridge fills it
 * itself: a compute dispatch reads the engine's depth image, maps every pixel's clip
 * position through `reprojection`, and stores the normalized-device difference into the
 * motion image mc_dlss_acquire_images returned. `reprojection` is 16 floats in
 * column-major order - the same layout GLSL and JOML use - and must be the jitter-free
 * reprojection, because the plugin is told this frame's jitter separately.
 *
 * The destination is the module's own motion image and never appears here.
 *
 * `render_width` and `render_height` must be the configured render dimensions. They are a
 * check that the caller and the configuration still agree, not a size to record at: a
 * mismatch is a caller that has lost track of its own configuration.
 *
 * Records and never submits: like mc_dlss_evaluate, the work is ordered by Minecraft's own
 * graphics submission. The depth image is handed back in the layout it arrived in. Calling
 * before initialize, before configure, or before the images are acquired records nothing
 * and fails.
 */
typedef struct McDlssMotionInfo {
    uint64_t command_buffer;
    McDlssImage depth;
    const float* reprojection;
    uint32_t render_width;
    uint32_t render_height;
} McDlssMotionInfo;

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_write_motion(const McDlssMotionInfo* info);

/*
 * Post-scene velocity merge for the velocity-MRT route, recorded on the caller's command
 * buffer.
 *
 * The engine's render-sized RG16_FLOAT velocity companion carries genuine object motion from
 * the scene writers and the invalid sentinel everywhere else. One compute dispatch samples
 * that companion (a render-attachment and sampled input, never a storage image) and the
 * engine's depth image, copies every non-sentinel object vector unchanged into the module's
 * own motion image, and reconstructs jitter-stripped camera reprojection for every pixel
 * whose payload is still the sentinel - including newly observed dynamic geometry without
 * object history. The complete merged field lands in the motion image
 * mc_dlss_acquire_images returned, which is the sole Streamline motion source on every
 * route.
 *
 * `reset` marks a frame with no valid predecessor, whose reprojection is the identity. Such
 * a frame must not read the identity as a still camera, and the destination still holds the
 * previous frame's merged field, so the dispatch writes the invalid sentinel everywhere
 * instead of reconstructing anything.
 *
 * `reprojection` is 16 floats in column-major order, the same layout as McDlssMotionInfo.
 * `render_width` and `render_height` must be the configured render dimensions, a check that
 * the caller and the configuration still agree.
 *
 * The companion stays in GENERAL, the layout the engine rests it in: the fill owns an
 * explicit barrier from the scene's color-attachment writes to its sampled reads, because
 * layout equality alone is not synchronization. The motion image is left in GENERAL like the
 * camera-only writer leaves it, and the depth image is handed back in the layout it arrived
 * in.
 *
 * Records and never submits: like the other recording calls, the work is ordered by
 * Minecraft's own graphics submission. Must precede mc_dlss_tag_sr_resources for the same
 * frame on the same command buffer: the tag names the module's motion image as the
 * motion-vector buffer, and the evaluation reads it. Calling before initialize, before
 * configure, or before the images are acquired records nothing and fails.
 */
typedef struct McDlssFillVelocityInfo {
    uint64_t command_buffer;
    McDlssImage depth;
    McDlssImage velocity;
    const float* reprojection;
    uint32_t render_width;
    uint32_t render_height;
    int32_t reset;
} McDlssFillVelocityInfo;

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_fill_velocity(const McDlssFillVelocityInfo* info);

/*
 * Copies the upscaled DLSS output into an engine image, recorded on the caller's command
 * buffer.
 *
 * The output image belongs to this module and Minecraft has no handle for it, so the
 * upscaled frame becomes visible only by being copied into the target the rest of the
 * frame composes over. Recorded after mc_dlss_evaluate on the same command buffer, which
 * is what orders the copy behind the evaluation that produced the image.
 *
 * The destination is handed back in the layout it arrived in. `width` and `height` must be
 * the configured output dimensions: a destination of any other size is a caller that has
 * lost track of its own configuration, not something to scale into. Calling before
 * initialize, before configure, or before the images are acquired records nothing and fails.
 *
 * The destination is the engine's output-sized colour target, a single-level, single-layer
 * 2D image; the subresource range is therefore derived, not carried, and only the image
 * handle is needed - never a view or a format.
 */
typedef struct McDlssPresentInfo {
    uint64_t command_buffer;
    uint64_t image;
    uint32_t width;
    uint32_t height;
} McDlssPresentInfo;

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_present_output(const McDlssPresentInfo* info);

/*
 * One frame's real camera, as Streamline's common constants need it: the jitter-free
 * view-to-clip and clip-to-view matrices, the camera's world-space position, and its
 * orthonormal right/up/forward basis.
 *
 * `view_to_clip` and `clip_to_view` are 16 floats each in row-major order (the layout
 * sl::float4x4 stores), already expressed in the image-space Y convention the DLSS-G plugin
 * reads: the engine's jitter-free view-to-clip projection (view bob and portal/nausea skew
 * included) with its Y column negated, and the matching inverse with its Y row negated, so
 * the pair still round-trips - the temporal-AA jitter travels separately as
 * `McDlssEvaluateInfo.jitter`. Streamline reads the matrices in the row-vector convention
 * (`v' = v * M`), where a projected point's clip-space Y travels through the projection's
 * column 1 (flat indices 1, 5, 9, 13) and the clip-space Y input travels through the
 * inverse's row 1 (flat indices 4..7); negating exactly those is the flip that keeps the
 * matrices in the same pixel<->NDC Y convention as the rasterized images and the motion
 * field. The module copies the floats straight into slSetConstants. `pos` is the camera
 * position in world space.
 * `right`, `up`, and `fwd` are the camera's world-space basis vectors: the directions of
 * view-space +X, +Y, and -Z (the direction the camera looks). Extracted from the view
 * rotation, they form an orthonormal basis, which the DLSS-G plugin's auto scene-change
 * detection verifies before it runs.
 *
 * All values are plain floats: 44 floats, no padding.
 */
typedef struct McDlssCameraConstants {
    float view_to_clip[16];
    float clip_to_view[16];
    float pos[3];
    float right[3];
    float up[3];
    float fwd[3];
} McDlssCameraConstants;

/*
 * Records the DLSS evaluation on the caller's command buffer, reading the low-resolution
 * world colour and depth, the module's own motion and output images, and writing the
 * upscaled frame into the output image.
 *
 * Only the engine's two images are carried. The motion and output images are this module's
 * own - allocated by mc_dlss_acquire_images from the configured dimensions - so passing them
 * back in would be the caller handing the module handles it already holds, and every such
 * handle is one the module would then have to validate against itself.
 *
 * `render_width` and `render_height` must be the configured render dimensions, for the same
 * reason as in McDlssMotionInfo. The output dimensions are not carried at all: nothing in
 * this call is sized from them that the module does not already own.
 *
 * Colour and depth are each a single-level, single-layer 2D image whose aspect follows from
 * its role (colour is a colour image, depth is a depth image); the subresource ranges are
 * therefore derived, not carried.
 *
 * `reset_history` must be 0 or 1: it clears the accumulated DLSS history for a frame that is
 * not continuous with the one before it.
 *
 * Must be called after mc_dlss_tag_sr_resources for the same frame on the same command buffer:
 * the evaluation records Streamline's per-frame constants and the feature evaluation against
 * the frame token the tag call obtained and retained, and evaluating with no retained token
 * fails. The direct-NGX feature lifecycle this call used to drive is retired.
 */
typedef struct McDlssEvaluateInfo {
    uint64_t command_buffer;
    McDlssImage color;
    McDlssImage depth;
    McDlssVec2 jitter;
    McDlssVec2 motion_scale;
    uint32_t render_width;
    uint32_t render_height;
    float frame_time_milliseconds;
    int32_t reset_history;
    // The frame's real camera, carried through the same call so the evaluation's single
    // slSetConstants records it together with the jitter, motion scale, and reset flag
    // under the frame's retained token. A caller that has no camera (an SR-only frame) may
    // leave it zero-filled: the module records whatever the struct carries.
    McDlssCameraConstants camera;
} McDlssEvaluateInfo;

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_evaluate(const McDlssEvaluateInfo* info);

/*
 * Reads back the camera constants the last successful evaluation recorded into Streamline's
 * common constants, as the constants oracle.
 *
 * The present-generation proof drives mc_dlss_evaluate with a known camera and reads this
 * back to prove the exact matrices and basis the caller handed in reached slSetConstants
 * unchanged - the constants the DLSS-G plugin interpolates the generated frame's camera
 * from. Answers not-initialized until an evaluation has recorded constants at least once
 * (reset_state clears the record with the rest of the struct, so a fresh fork refuses), and
 * invalid-parameter for a null out pointer.
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_camera_constants(McDlssCameraConstants* out);

/*
 * Tags one frame's DLSS SR resources on the caller's command buffer, through Streamline's
 * frame-based tagging (slGetNewFrameToken + slSetTagForFrame).
 *
 * `color` and `depth` are the engine's render-sized colour and depth images, tagged as the
 * scaling-input colour and the depth. The motion source is always the module's own motion
 * image - filled by mc_dlss_write_motion on the camera-only route and by
 * mc_dlss_fill_velocity on the velocity-MRT route - so no engine velocity companion is
 * carried or tagged; direct companion tagging is retired. When the module's own output image
 * has been acquired for the configured dimensions (mc_dlss_acquire_images), it is tagged as
 * the scaling-output colour as well; until then the call still succeeds with just the
 * engine's inputs, so tagging can run from the first frame on.
 *
 * The frame's GPU timing chain is opened by whichever call recorded the motion stage - the
 * compute writer on the camera-only route, the sentinel fill on the velocity route. This
 * call opens neither, and tags only.
 *
 * Must be called after mc_dlss_bootstrap_streamline, mc_dlss_activate_vulkan_proxies, and
 * mc_dlss_configure. Records on and never submits `command_buffer`, like the other recording
 * calls.
 */
typedef struct McDlssTagInfo {
    uint64_t command_buffer;
    McDlssImage color;
    McDlssImage depth;
} McDlssTagInfo;

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_tag_sr_resources(const McDlssTagInfo* info);

/*
 * Tags one frame's DLSS-G resources on the caller's command buffer, through Streamline's
 * frame-based tagging (slGetNewFrameToken + slSetTagForFrame).
 *
 * `depth` is the engine's render-sized depth image, tagged as the DLSS-G depth input.
 * `hudless` is the engine's output-sized HUD-less colour, tagged as the DLSS-G HUD-less
 * input; `ui` is the engine's output-sized UI colour+alpha target, tagged as the DLSS-G UI
 * input. The motion source is always the module's own motion image - filled by
 * mc_dlss_write_motion on the camera-only route and by mc_dlss_fill_velocity on the
 * velocity-MRT route - so no engine velocity companion is carried or tagged. The backbuffer/
 * output chain is present interception rather than a tag, so no output image is carried or
 * tagged here.
 *
 * The tagged formats are fixed to the ones mc_dlss_configure_fg records: the depth must be
 * VK_FORMAT_D32_SFLOAT and both colour buffers VK_FORMAT_R8G8B8A8_UNORM; anything else
 * answers FAIL_InvalidParameter, because the plugin allocates its internal resources against
 * the option-declared formats and a tag naming a different format would disagree with them.
 * The call also answers FAIL_InvalidParameter until mc_dlss_configure_fg has recorded the
 * DLSS-G options for the stored configuration and mc_dlss_acquire_images has created the
 * module's images at the configured dimensions: the frame's four tags - depth, motion,
 * HUD-less, and UI - always record together, never as a partial set.
 *
 * The declared layouts name where the images rest when the frame is tagged: Minecraft rests
 * every texture in GENERAL and the motion fill leaves the module's image in GENERAL, and
 * nothing in this call transitions them. The later evaluation and present slices own the
 * transitions that move the inputs before DLSS-G reads them and must update the declared
 * layouts with them.
 *
 * The frame token this call obtains and retains is shared with mc_dlss_tag_sr_resources for
 * the same frame: a repeated tag reuses the token rather than advancing the frame, and the
 * frame's evaluation consumes it. Must be called after mc_dlss_bootstrap_streamline,
 * mc_dlss_activate_vulkan_proxies, mc_dlss_configure, mc_dlss_configure_fg, and
 * mc_dlss_acquire_images. Records on and never submits `command_buffer`, like the other
 * recording calls.
 */
typedef struct McDlssFgTagInfo {
    uint64_t command_buffer;
    McDlssImage depth;
    McDlssImage hudless;
    McDlssImage ui;
} McDlssFgTagInfo;

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_tag_fg_resources(const McDlssFgTagInfo* info);

/*
 * Records the frame's present-handoff eligibility: re-records the stored DLSS-G 2x options
 * through slDLSSGSetOptions with the back-buffer count the last successful
 * mc_dlss_configure_fg declared, accepting exactly one complete current-frame SR+FG tag set
 * under equal frame indexes.
 *
 * The guide requires slDLSSGSetOptions per frame, and the present-driven DLSS-G path reads
 * the frame's SR and FG tags at present time, so this is the call the mod makes once per
 * frame immediately before Present: the handoff only accepts a frame whose DLSS-G options
 * recorded for the stored configuration, whose module images exist at the configured size,
 * and whose SR and FG tags both recorded fresh under the same frame index (the token-reuse
 * equality mc_dlss_query_tagged_frame_indexes reports). Missing options, partial tags, and
 * consumed eligibility - a tag set that already handed off - answer FAIL_InvalidParameter
 * before anything is re-recorded, so a refused handoff clears no tag state and re-records no
 * options. A successful handoff consumes the frame's tag set by clearing both sides' tag
 * records (so the set is eligible again only when both sides re-record under equal indexes -
 * repeating one side alone stays refused) and arms the present bracket: the retained token
 * survives the handoff, because the bracket's Reflex markers are emitted around the actual
 * queue present by mc_dlss_present_start and mc_dlss_present_end, and the END consumes the
 * token. A bracket whose PRESENT_END marker failed also consumes the frame exactly like a
 * successful one: its START already reached the Reflex plugin, so a retry would emit a
 * second START for the same frame, and the FAIL result returns with the frame ineligible
 * instead.
 *
 * Records no GPU work: the frame's tagged resources stay in the layouts the tags declared
 * (GENERAL depth and motion, eValidUntilPresent lifetime) until Streamline's present path
 * consumes them. Must be called after mc_dlss_bootstrap_streamline,
 * mc_dlss_activate_vulkan_proxies, mc_dlss_configure, mc_dlss_configure_fg, and
 * mc_dlss_acquire_images.
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_present_handoff(void);

/*
 * Emits the PRESENT_START Reflex marker of the armed present bracket under the frame's
 * retained token, on the caller's (present) thread immediately before the queue present.
 *
 * The DLSS-G guide requires the frame index carried by the Reflex present markers to match
 * the index carried by the common constants, and the plugin correlates the presented frame
 * with its constants through PRESENT_START - a frame presented without it never generates.
 * The marker call reaches the plugin directly and is recorded in the present-marker log
 * immediately; the frame's tag set was already consumed by the handoff, so the bracket's
 * half is the token armed by mc_dlss_present_handoff and consumed by mc_dlss_present_end.
 * Answers FAIL_NotInitialized while the Streamline session is not ready. A present without
 * an armed bracket - an SR-only or skipped frame, or any present of a session that never
 * handed off - is a no-op success: the present seam fires on every present, and a refusal
 * would fail a frame that simply did not compose. A second START for an already-open
 * bracket (a present that threw between START and END) is the same no-op, so one frame
 * can never receive two START markers.
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_present_start(void);

/*
 * Emits the frame's Reflex/PCL markers at the M-12 input, simulation, and render-submit
 * seams: mc_dlss_reflex_input_sample at Minecraft's GLFW input poll, the simulation pair
 * around Minecraft's runTick simulation, and the render-submit pair around renderFrame's
 * command-encoder submit. All five emit through slPCLSetMarker under the retained
 * Streamline frame token, so the whole marker surface shares the token identity the
 * frame's SR/FG tags and common constants record under.
 *
 * The input-sample entry is the frame-start seam: it obtains the frame's token
 * (slGetNewFrameToken is called again even when a token is retained, replacing one a
 * previous frame never consumed), runs the unconditional slReflexSleep against it, and
 * emits the input marker. The pinned Streamline 2.12 vocabulary removed eInputSample from
 * PCLMarker, so the input seam emits ePCLatencyPing: the PCL guide defines the ping as
 * the marker whose frame index identifies which frame picks up the simulated input, which
 * is exactly the input-sample seam semantics the contract retains. The simulation and
 * render-submit entries emit eSimulationStart/eSimulationEnd and
 * eRenderSubmitStart/eRenderSubmitEnd.
 *
 * Every entry answers FAIL_NotInitialized while the Streamline session is not ready, and
 * the simulation and render-submit entries also answer FAIL_NotInitialized when no frame
 * token is retained (a frame that never ran its input sample emits no markers). A refused
 * or failed marker call emits nothing and never latches the session: the markers are the
 * PCL/Reflex diagnostic surface, not a frame-route stage, and a missing ping must not
 * degrade the frames that rendered anyway.
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_reflex_input_sample(void);
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_reflex_simulate_start(void);
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_reflex_simulate_end(void);
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_reflex_render_submit_start(void);
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_reflex_render_submit_end(void);

/*
 * The reflex-marker oracle: how many of each of the five Reflex/PCL markers this module has
 * actually emitted (per-type cumulative counts), how many marker events in total, and the
 * recent event log in emission order. Each log entry is a (type, frame index) pair: the
 * type is 0 for INPUT_SAMPLE, 1 for SIMULATION_START, 2 for SIMULATION_END, 3 for
 * RENDER_SUBMIT_START, and 4 for RENDER_SUBMIT_END, and the frame index is the Streamline
 * frame token the marker was emitted under.
 *
 * The frame index must equal the frame indexes the frame's SR/FG tags (and its common
 * constants and present markers) recorded under: the input sample obtains the retained
 * token the rest of the frame reuses, so the log's equality with
 * mc_dlss_query_tagged_frame_indexes is what proves the marker surface shares the retained
 * token identity. The per-type counts advance by exactly one per emitted marker and stay
 * unchanged across refused or pre-ready calls, which is what proves the "refused sessions
 * emit none" half of the contract.
 *
 * `type_counts` receives the five per-type counts in order (at least five entries),
 * `events` the most recent events in emission order, at most `events_capacity` pairs (and
 * never more than the module's log holds); the counts answer the whole session. All three
 * out-pointers are written on success. Answers FAIL_NotInitialized until at least one
 * marker was actually emitted; the oracle is module history rather than per-frame
 * eligibility, so it keeps answering across later refusals and resets of the frame's
 * eligibility, and only reset_state clears it.
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_reflex_markers(
    uint32_t* type_counts,
    uint32_t* event_count,
    uint32_t* events,
    uint32_t events_capacity);

/*
 * The reflex-options oracle: the sl::ReflexMode value the READY transition's
 * slReflexSetOptions registration recorded (1 = eLowLatency) and how many
 * slReflexSetOptions calls this session made.
 *
 * The pinned Reflex guide requires the app to call slReflexSetOptions at least once even
 * with Reflex Low Latency off and no Reflex UI, and says not to repeat the call per frame
 * while the options do not change; mc_dlss_initialize makes that one call, and this oracle
 * proves it: `mode` is eLowLatency once the record succeeded, and `set_options_calls` is 1
 * after one READY transition and stays 1 across idempotent re-initializes, composed frames,
 * and resets. Answers FAIL_NotInitialized while the Streamline session is not ready and
 * FAIL_InvalidParameter while no record exists or either out-pointer is null.
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_reflex_options(uint32_t* mode,
                                                               uint32_t* set_options_calls);

/*
 * Emits the PRESENT_END Reflex marker of the armed present bracket under the frame's
 * retained token, on the caller's (present) thread immediately after the queue present
 * returned, and consumes the bracket.
 *
 * The END closes the bracket the START opened around the queue present, and emits only
 * after a START actually reached the plugin: a bracket whose START never emitted (its
 * marker call failed, or the END arrived without one) closes without a marker, so the
 * log never reads an END without its START. Whether the marker call succeeded or failed,
 * the bracket is the composed frame's terminal act: the frame is consumed exactly like a
 * successful one, so a retry cannot emit a second START for the same frame, and the next
 * frame's tags obtain a fresh token under a fresh index. Answers FAIL_NotInitialized
 * while the Streamline session is not ready; a present without an armed bracket is a
 * no-op success like the START.
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_present_end(void);

/*
 * The present-marker oracle: how many PRESENT_START and PRESENT_END markers this module has
 * actually emitted (per-type cumulative counts), how many marker events in total, and the
 * recent event log in emission order. Each log entry is a (type, frame index) pair: the
 * type is 0 for PRESENT_START and 1 for PRESENT_END, and the frame index is the Streamline
 * frame token the marker was emitted under.
 *
 * The frame index must equal the frame indexes the frame's SR and FG tags (and the common
 * constants) recorded under: the handoff emits both markers against the same retained
 * frame token the tags and the constants used, so the log's equality is what proves the
 * present bracket correlates with the frame DLSS-G generates. The per-type counts must each
 * advance by exactly one per successful handoff and stay unchanged across refused or
 * pre-ready handoffs, which is what proves the "exactly one PRESENT_START then PRESENT_END"
 * half of the present-marker contract: the START and END events are recorded separately and
 * in emission order, so a handoff whose END marker failed reads as one START event and no
 * END rather than as a pair that never happened.
 *
 * `events` receives the most recent events in emission order, at most `events_capacity`
 * pairs (and never more than the module's log holds); the counts answer the whole session.
 * All four out-pointers are written on success. Answers FAIL_NotInitialized until at least
 * one marker was actually emitted; the oracle is module history rather than per-frame
 * eligibility, so it keeps answering across later refusals and resets of the frame's
 * eligibility, and only reset_state clears it.
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_present_markers(
    uint32_t* start_count,
    uint32_t* end_count,
    uint32_t* event_count,
    uint32_t* events,
    uint32_t events_capacity);

/*
 * Blocks until Streamline's DLSS-G input processing for the previously presented frame has
 * completed, on the caller's (present/render) thread and through the Vulkan device.
 *
 * The DLSS-G options record eBlockNoClientQueues, under which the DLSS-G plugin reads the
 * tagged inputs of a presented frame on its own queues after Present; the programming guide
 * requires the host to wait on DLSSGState::inputsProcessingCompletionFence - a Vulkan
 * timeline semaphore on this API, read together with its value
 * DLSSGState::lastPresentInputsProcessingCompletionFenceValue via slDLSSGGetState - before it
 * modifies or destroys those inputs in a later frame. This is the call the mod makes at the
 * start of an FG-active frame, before the world phase rewrites the tagged depth, motion,
 * HUD-less, and UI inputs.
 *
 * Refuses FAIL_NotInitialized while the Streamline session is not ready (bootstrap and proxy
 * activation not both complete, or the Vulkan device tuple never recorded) and
 * FAIL_InvalidParameter while the DLSS-G options have not recorded for the stored
 * configuration, the same gates as mc_dlss_tag_fg_resources. A null semaphore - the plugin
 * has not allocated one, as before the first present - is a no-op success: there is no input
 * processing in flight to wait for. The call deliberately does not look at the reported
 * DLSSGStatus; the status-to-off fallback is a later slice's own.
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_wait_fg_inputs_idle(void);

/*
 * The wait oracle: performs the same value-aware Vulkan timeline-semaphore wait
 * mc_dlss_wait_fg_inputs_idle performs, on explicit device and semaphore handles and an
 * explicit value, so the wait's value semantics are provable without a live Streamline
 * session. The headless proof creates its own timeline semaphore, waits for a value the
 * semaphore has not reached, and observes the call block until that value is signaled;
 * waiting for any lower value (or treating the semaphore as a VkFence) would answer
 * immediately and fail the proof.
 *
 * Refuses FAIL_InvalidParameter when either handle is null. Touches no module or Streamline
 * state and blocks on the device like the session-driven entry.
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_wait_fg_inputs_value(uint64_t vk_device,
                                                               uint64_t semaphore,
                                                               uint64_t value);

/*
 * Reads the live DLSS-G state through slDLSSGGetState: the DLSSGStatus word, actual
 * presentations per app frame (two means one real plus one generated), the input-processing
 * completion timeline semaphore last reached, and the semaphore handle itself.
 *
 * The present-generation proof reads this through the ABI to observe the interposed
 * vkQueuePresentKHR path working: eDLSSGStatusOk (word zero) after presents, a presentation
 * factor above one, and a completion-fence value that
 * advances with every presented frame the plugin processed - the same value
 * mc_dlss_wait_fg_inputs_idle waits on, read from the same query, so the two always travel
 * together. Refuses FAIL_NotInitialized while the Streamline session is not ready and
 * FAIL_InvalidParameter while the DLSS-G options have not recorded (the same gates as
 * mc_dlss_tag_fg_resources) or any output pointer is null. The read performs no GPU work
 * and never blocks.
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_fg_state(uint32_t* status,
                                                         uint32_t* num_frames_presented,
                                                         uint64_t* last_present_inputs_processing_fence_value,
                                                         uint64_t* inputs_processing_completion_fence);

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_reset(void);
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_close(void);

/*
 * mc_dlss_reset releases the module-owned images and drops the retained Streamline frame
 * token and the SR/FG tag records, so the next tag obtains a fresh token and a handoff
 * cannot accept a tag set recorded before the reset; the session stays ready for the next
 * acquire. mc_dlss_close releases the module-owned Vulkan resources (timing, motion pass,
 * images) and the retained frame token, shuts the process-wide Streamline runtime down
 * (while the caller's device is still alive), and forgets the bootstrap, proxy, and session
 * bookkeeping - a later mc_dlss_bootstrap_streamline then reinitializes the runtime.
 * Neither releases direct-NGX ownership, because there is none.
 */

#ifdef __cplusplus
}
#endif

#endif
