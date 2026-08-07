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
 * Flat ABI used by Java 25 FFM. NVIDIA NGX headers, handles, parameters, and
 * result decoding remain private to the native implementation.
 *
 * Every function returns 1 on success and an NGX/native result code otherwise.
 * The bridge owns NGX feature and parameter lifetimes.
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
 * Pre-creation NGX extension requirements. Must be called before the Vulkan
 * instance and device are created; the returned names are the exact
 * NVSDK_NGX_VULKAN_GetFeatureInstanceExtensionRequirements and
 * NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements strings.
 *
 * First call with index==0 && name==NULL && name_capacity==0 returns the
 * extension count in *extension_count. Subsequent calls with a valid name
 * buffer copy the i-th extension name (NUL terminated) and return the count.
 * Returns an NGX/native result code on failure (e.g. FAIL_InvalidParameter).
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

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_initialize(
    uint64_t vk_instance,
    uint64_t vk_physical_device,
    uint64_t vk_device,
    const char* sdk_path,
    const char* data_path);

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_optimal_dimensions(
    uint32_t output_width,
    uint32_t output_height,
    uint32_t quality_mode,
    uint32_t* render_width,
    uint32_t* render_height);

/*
 * Stores the dimensions, the NGX performance/quality mode, and the DLSS render
 * preset the next feature creation uses. Owns no command buffer and creates no
 * feature.
 *
 * `quality_mode` is an NVSDK_NGX_PerfQuality_Value: MaxPerf, Balanced,
 * MaxQuality, UltraPerformance, or DLAA. UltraQuality is defined by NGX and not
 * implemented by it, so it is rejected here rather than passed through.
 *
 * `render_preset` is an NVSDK_NGX_DLSS_Hint_Render_Preset. It is written onto
 * the capability parameters immediately before feature creation, which is the
 * only point NGX reads it; changing it recreates the feature exactly like a
 * dimension or mode change.
 *
 * These dimensions are the single source of truth for everything sized from them: the
 * images mc_dlss_acquire_images allocates, the feature the next evaluation creates, and
 * the sizes the recording calls check their callers against.
 */
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_configure(
    uint32_t output_width,
    uint32_t output_height,
    uint32_t render_width,
    uint32_t render_height,
    uint32_t quality_mode,
    uint32_t render_preset);

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
 * Measured with device timestamps around the motion pass, the NGX evaluation, and the copy
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
 * reprojection, because NGX is told this frame's jitter separately.
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

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_reset(void);
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_close(void);

#ifdef __cplusplus
}
#endif

#endif
