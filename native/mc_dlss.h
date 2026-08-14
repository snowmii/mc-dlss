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
 * Records the DLSS-G per-frame 2x options with Streamline's slDLSSGSetOptions.
 *
 * Must be called after mc_dlss_bootstrap_streamline and mc_dlss_activate_vulkan_proxies and
 * after a successful mc_dlss_configure: the record answers FAIL_NotInitialized without a
 * ready Streamline session and FAIL_InvalidParameter while the stored configuration still
 * holds zero dimensions. The call owns no command buffer, tags nothing, and creates no
 * feature - it only records what the next present applies.
 *
 * The record is fixed at the contract's single multiplier: mode eOn with
 * numFramesToGenerate = 1 (2x). The retained-resources flag, the UI-recomposition switch,
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
} McDlssEvaluateInfo;

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_evaluate(const McDlssEvaluateInfo* info);

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
 * records; each side's next successful tag record re-arms only its own half, so the set is
 * eligible again only when both sides re-record under equal indexes - repeating one side
 * alone stays refused. A handoff whose PRESENT_START marker succeeded but whose PRESENT_END
 * marker failed also consumes the frame exactly like a successful one: its START already
 * reached the Reflex plugin, so a retry would emit a second START for the same frame, and
 * the FAIL result returns with the frame ineligible instead.
 *
 * Records no GPU work: the frame's tagged resources stay in the layouts the tags declared
 * (GENERAL depth and motion, eValidUntilPresent lifetime) until Streamline's present path
 * consumes them. Must be called after mc_dlss_bootstrap_streamline,
 * mc_dlss_activate_vulkan_proxies, mc_dlss_configure, mc_dlss_configure_fg, and
 * mc_dlss_acquire_images.
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_present_handoff(void);

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
