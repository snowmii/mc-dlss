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
 */
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

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_configure(
    uint32_t output_width,
    uint32_t output_height,
    uint32_t render_width,
    uint32_t render_height,
    uint32_t quality_mode);

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_evaluate(
    uint64_t command_buffer,
    uint64_t color_view,
    uint64_t color_image,
    uint32_t color_format,
    uint32_t color_aspect_mask,
    uint32_t color_base_mip_level,
    uint32_t color_level_count,
    uint32_t color_base_array_layer,
    uint32_t color_layer_count,
    uint64_t depth_view,
    uint64_t depth_image,
    uint32_t depth_format,
    uint32_t depth_aspect_mask,
    uint32_t depth_base_mip_level,
    uint32_t depth_level_count,
    uint32_t depth_base_array_layer,
    uint32_t depth_layer_count,
    uint64_t motion_view,
    uint64_t motion_image,
    uint32_t motion_format,
    uint32_t motion_aspect_mask,
    uint32_t motion_base_mip_level,
    uint32_t motion_level_count,
    uint32_t motion_base_array_layer,
    uint32_t motion_layer_count,
    uint64_t output_view,
    uint64_t output_image,
    uint32_t output_format,
    uint32_t output_aspect_mask,
    uint32_t output_base_mip_level,
    uint32_t output_level_count,
    uint32_t output_base_array_layer,
    uint32_t output_layer_count,
    uint32_t render_width,
    uint32_t render_height,
    uint32_t output_width,
    uint32_t output_height,
    float jitter_x,
    float jitter_y,
    float motion_scale_x,
    float motion_scale_y,
    float frame_time_milliseconds,
    int32_t reset_history);

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_reset(void);
MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_close(void);

#ifdef __cplusplus
}
#endif

#endif
