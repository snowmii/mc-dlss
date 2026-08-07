/*
 * The exported ABI surface, and nothing else.
 *
 * Every function here does the same four things and delegates the rest: take the lock, check
 * the session and the caller's arguments, drive the units that own the work, and translate
 * whatever comes back into a result code. The frame-stage timestamps are stamped here too, so
 * the recorded sequence for a frame - motion, evaluation, copy - reads in one place rather
 * than being spread across the units that make it up.
 */
#include "mc_dlss.h"

#include "internal/common.h"
#include "internal/images.h"
#include "internal/motion.h"
#include "internal/ngx.h"
#include "internal/session.h"
#include "internal/state.h"
#include "internal/timing.h"

#include <nvsdk_ngx_vk.h>

#include <mutex>
#include <string>

using namespace mc_dlss;

namespace {

constexpr char kProjectId[] = "50f68c51-c7be-49bd-a875-f73045f88d27";
constexpr char kEngineVersion[] = "Minecraft 26.2";

// The recording calls all name the render dimensions they believe are configured. The value is
// never used as a size - everything is sized from the configuration - so this is purely the
// check that the caller has not lost track of what it configured.
bool matches_configured_render_size(const uint32_t width, const uint32_t height) noexcept {
    return width != 0 && height != 0 && width == g_state.renderWidth &&
           height == g_state.renderHeight;
}

// The module's own images have to exist, at the size this frame is being recorded for, before
// anything can be recorded into or out of them.
bool images_match_configuration() noexcept {
    return g_state.motionImage.view != VK_NULL_HANDLE &&
           g_state.outputImage.view != VK_NULL_HANDLE &&
           g_state.imagesRenderWidth == g_state.renderWidth &&
           g_state.imagesRenderHeight == g_state.renderHeight &&
           g_state.imagesOutputWidth == g_state.outputWidth &&
           g_state.imagesOutputHeight == g_state.outputHeight;
}

} // namespace

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_instance_extension(
    const uint32_t index, char* name, const uint32_t name_capacity,
    uint32_t* extension_count) {
    try {
        const NVSDK_NGX_FeatureDiscoveryInfo dis = make_discovery_info();
        uint32_t count = 0;
        VkExtensionProperties* properties = nullptr;
        const NVSDK_NGX_Result result = NVSDK_NGX_VULKAN_GetFeatureInstanceExtensionRequirements(
            &dis, &count, &properties);
        if (result != NVSDK_NGX_Result_Success) {
            return static_cast<int32_t>(result);
        }
        return copy_extension_name(index, name, name_capacity, extension_count, count, properties);
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_device_extension(
    const uint64_t vk_instance, const uint64_t vk_physical_device,
    const uint32_t index, char* name, const uint32_t name_capacity,
    uint32_t* extension_count) {
    try {
        if (vk_instance == 0 || vk_physical_device == 0) {
            return kInvalidParameter;
        }
        const NVSDK_NGX_FeatureDiscoveryInfo dis = make_discovery_info();
        uint32_t count = 0;
        VkExtensionProperties* properties = nullptr;
        const NVSDK_NGX_Result result = NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements(
            from_uint64<VkInstance>(vk_instance),
            from_uint64<VkPhysicalDevice>(vk_physical_device),
            &dis, &count, &properties);
        if (result != NVSDK_NGX_Result_Success) {
            return static_cast<int32_t>(result);
        }
        return copy_extension_name(index, name, name_capacity, extension_count, count, properties);
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_initialize(const uint64_t vk_instance,
                                                     const uint64_t vk_physical_device,
                                                     const uint64_t vk_device,
                                                     const char* sdk_path,
                                                     const char* data_path) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (vk_instance == 0 || vk_physical_device == 0 || vk_device == 0) {
            return kInvalidParameter;
        }
        std::wstring sdkPath;
        std::wstring dataPath;
        if (!utf8_to_wide(sdk_path, sdkPath) || !utf8_to_wide(data_path, dataPath)) {
            return kInvalidParameter;
        }

        if (g_state.initialized) {
            const bool sameDevice = g_state.instanceValue == vk_instance &&
                                    g_state.physicalDeviceValue == vk_physical_device &&
                                    g_state.deviceValue == vk_device &&
                                    g_state.sdkPath == sdkPath && g_state.dataPath == dataPath;
            if (!sameDevice) {
                // A different device/path cannot replace live NGX ownership without close.
                return kInvalidParameter;
            }
            if (g_state.bootstrapComplete) {
                return kSuccess;
            }
            // A prior bootstrap failed during cleanup. Retry only after releasing every
            // ownership still held by this state.
            const int32_t cleanupResult = shutdown_state();
            if (cleanupResult != kSuccess) {
                return cleanupResult;
            }
        }

        g_state.instanceValue = vk_instance;
        g_state.physicalDeviceValue = vk_physical_device;
        g_state.deviceValue = vk_device;
        g_state.instance = from_uint64<VkInstance>(vk_instance);
        g_state.physicalDevice = from_uint64<VkPhysicalDevice>(vk_physical_device);
        g_state.device = from_uint64<VkDevice>(vk_device);
        g_state.sdkPath = std::move(sdkPath);
        g_state.dataPath = std::move(dataPath);

        const wchar_t* featureSearchPath = g_state.sdkPath.c_str();
        NVSDK_NGX_FeatureCommonInfo featureInfo{};
        featureInfo.PathListInfo.Path = &featureSearchPath;
        featureInfo.PathListInfo.Length = 1;

        const NVSDK_NGX_Result initResult = NVSDK_NGX_VULKAN_Init_with_ProjectID(
            kProjectId, NVSDK_NGX_ENGINE_TYPE_CUSTOM, kEngineVersion, g_state.dataPath.c_str(),
            g_state.instance, g_state.physicalDevice, g_state.device, nullptr, nullptr,
            &featureInfo, NVSDK_NGX_Version_API);
        if (initResult != NVSDK_NGX_Result_Success) {
            reset_state();
            return static_cast<int32_t>(initResult);
        }
        g_state.initialized = true;

        NVSDK_NGX_Result result =
            NVSDK_NGX_VULKAN_GetCapabilityParameters(&g_state.capabilityParameters);
        if (result != NVSDK_NGX_Result_Success) {
            return cleanup_after_initialize_failure(static_cast<int32_t>(result));
        }
        if (g_state.capabilityParameters == nullptr) {
            return cleanup_after_initialize_failure(kInvalidParameter);
        }

        int available = 0;
        result = NVSDK_NGX_Parameter_GetI(g_state.capabilityParameters,
                                          NVSDK_NGX_Parameter_SuperSampling_Available,
                                          &available);
        if (result != NVSDK_NGX_Result_Success) {
            return cleanup_after_initialize_failure(static_cast<int32_t>(result));
        }
        if (available <= 0) {
            return cleanup_after_initialize_failure(kFeatureNotSupported);
        }
        g_state.bootstrapComplete = true;
        return kSuccess;
    } catch (...) {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_state.initialized || g_state.capabilityParameters != nullptr) {
            const int32_t cleanupResult = shutdown_state();
            return cleanupResult == kSuccess ? kFailure : cleanupResult;
        }
        reset_state();
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_optimal_dimensions(
    const uint32_t output_width, const uint32_t output_height, const uint32_t quality_mode,
    uint32_t* render_width, uint32_t* render_height) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return query_optimal_dimensions(output_width, output_height, quality_mode, render_width,
                                        render_height);
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_configure(const uint32_t output_width,
                                                    const uint32_t output_height,
                                                    const uint32_t render_width,
                                                    const uint32_t render_height,
                                                    const uint32_t quality_mode,
                                                    const uint32_t render_preset) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_state.bootstrapComplete) {
            return kNotInitialized;
        }
        if (!valid_dimensions(output_width, output_height, render_width, render_height) ||
            !valid_quality_mode(quality_mode) || !valid_render_preset(render_preset)) {
            return kInvalidParameter;
        }
        g_state.outputWidth = output_width;
        g_state.outputHeight = output_height;
        g_state.renderWidth = render_width;
        g_state.renderHeight = render_height;
        g_state.qualityMode = quality_mode;
        g_state.renderPreset = render_preset;
        return kSuccess;
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_acquire_images(McDlssImage* motion,
                                                         McDlssImage* output) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_state.bootstrapComplete) {
            return kNotInitialized;
        }
        if (motion == nullptr || output == nullptr) {
            return kInvalidParameter;
        }

        const int32_t result = acquire_images();
        if (result != kSuccess) {
            return result;
        }

        motion->view = to_uint64(g_state.motionImage.view);
        motion->image = to_uint64(g_state.motionImage.image);
        motion->format = static_cast<uint32_t>(kMotionFormat);
        output->view = to_uint64(g_state.outputImage.view);
        output->image = to_uint64(g_state.outputImage.image);
        output->format = static_cast<uint32_t>(kOutputFormat);
        return kSuccess;
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_release_images(void) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        release_images();
        return kSuccess;
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_frame_timings(float* motion_ms, float* evaluate_ms,
                                                             float* present_ms, float* total_ms) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (motion_ms == nullptr || evaluate_ms == nullptr || present_ms == nullptr ||
            total_ms == nullptr) {
            return kInvalidParameter;
        }
        if (!g_timing.hasResult) {
            return kNotInitialized;
        }
        *motion_ms = g_timing.motionMs;
        *evaluate_ms = g_timing.evaluateMs;
        *present_ms = g_timing.presentMs;
        *total_ms = g_timing.totalMs;
        return kSuccess;
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_wait_device_idle(void) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_state.device == VK_NULL_HANDLE) {
            return kSuccess;
        }
        return vkDeviceWaitIdle(g_state.device) == VK_SUCCESS ? kSuccess : kFailure;
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_write_motion(const McDlssMotionInfo* info) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_state.bootstrapComplete) {
            return kNotInitialized;
        }
        if (info == nullptr || info->command_buffer == 0 || info->reprojection == nullptr ||
            !matches_configured_render_size(info->render_width, info->render_height) ||
            !valid_image(info->depth)) {
            return kInvalidParameter;
        }
        // The motion image is the destination, so there is nothing to write into until it
        // has been acquired for the configuration this call names.
        if (!images_match_configuration()) {
            return kNotInitialized;
        }

        // The motion pass is the first thing this module records in a frame, so the frame's
        // timing opens here and everything the chain costs falls inside it.
        const VkCommandBuffer recordingBuffer =
            from_uint64<VkCommandBuffer>(info->command_buffer);
        begin_frame_timing(recordingBuffer);
        const int32_t result = record_motion(*info);
        if (result != kSuccess) {
            return result;
        }
        mark_frame_timing(recordingBuffer, 1);
        return kSuccess;
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_evaluate(const McDlssEvaluateInfo* info) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_state.bootstrapComplete) {
            return kNotInitialized;
        }
        if (info == nullptr || info->command_buffer == 0 || info->reset_history < 0 ||
            info->reset_history > 1 ||
            !matches_configured_render_size(info->render_width, info->render_height) ||
            !valid_image(info->color) || !valid_image(info->depth)) {
            return kInvalidParameter;
        }
        // The motion image is read and the output image is written, so both have to exist at
        // the size this frame is being recorded for.
        if (!images_match_configuration()) {
            return kNotInitialized;
        }

        const VkCommandBuffer recordingBuffer =
            from_uint64<VkCommandBuffer>(info->command_buffer);
        const int32_t featureResult = ensure_feature(recordingBuffer);
        if (featureResult != kSuccess) {
            return featureResult;
        }

        // Inputs into a read state and the output into a storage state, recorded on the
        // engine's own command buffer immediately before the evaluation reads them.
        const uint64_t motionImage = to_uint64(g_state.motionImage.image);
        const uint64_t outputImage = to_uint64(g_state.outputImage.image);
        const VkImageLayout colorEntryLayout = current_layout_of(info->color.image);
        const VkImageLayout depthEntryLayout = current_layout_of(info->depth.image);
        record_layout_transition(recordingBuffer, from_uint64<VkImage>(info->color.image),
                                 image_range_of(false), colorEntryLayout, kDlssInputLayout);
        record_layout_transition(recordingBuffer, from_uint64<VkImage>(info->depth.image),
                                 image_range_of(true), depthEntryLayout, kDlssInputLayout);
        record_layout_transition(recordingBuffer, g_state.motionImage.image, image_range_of(false),
                                 current_layout_of(motionImage), kDlssInputLayout);
        note_layout_after_transition(motionImage, kDlssInputLayout);
        record_layout_transition(recordingBuffer, g_state.outputImage.image, image_range_of(false),
                                 current_layout_of(outputImage), kDlssOutputLayout);
        note_layout_after_transition(outputImage, kDlssOutputLayout);

        const int32_t evaluateResult = record_evaluation(*info, recordingBuffer);

        // The engine's images go back where Minecraft expects to find them, in the same
        // recording, whether or not the evaluation succeeded: the transitions above were
        // recorded either way, and a command buffer that is submitted with them half-undone
        // hands the renderer an image in a layout its next pass does not expect. The two
        // native images keep the layouts DLSS restores them to, which is where the next
        // frame's transitions start from.
        record_layout_transition(recordingBuffer, from_uint64<VkImage>(info->color.image),
                                 image_range_of(false), kDlssInputLayout, colorEntryLayout);
        record_layout_transition(recordingBuffer, from_uint64<VkImage>(info->depth.image),
                                 image_range_of(true), kDlssInputLayout, depthEntryLayout);
        mark_frame_timing(recordingBuffer, 2);
        return evaluateResult;
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_present_output(const McDlssPresentInfo* info) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_state.bootstrapComplete) {
            return kNotInitialized;
        }
        if (info == nullptr || info->command_buffer == 0 || info->image == 0 ||
            info->width != g_state.outputWidth || info->height != g_state.outputHeight) {
            return kInvalidParameter;
        }
        // Nothing to present until the output image exists at the size this call names.
        if (g_state.outputImage.image == VK_NULL_HANDLE ||
            g_state.imagesOutputWidth != info->width ||
            g_state.imagesOutputHeight != info->height) {
            return kNotInitialized;
        }
        // Copying an image onto itself would be a caller that passed this module's own
        // output back as the engine target, and vkCmdCopyImage forbids the overlap.
        if (info->image == to_uint64(g_state.outputImage.image)) {
            return kInvalidParameter;
        }

        const VkCommandBuffer recordingBuffer =
            from_uint64<VkCommandBuffer>(info->command_buffer);
        const VkImage destination = from_uint64<VkImage>(info->image);
        // The destination is the engine's output-sized colour target - a single-level,
        // single-layer 2D image - so the copy is one mip, one layer, at the same extent, and
        // the range is the same constant the input transitions use.
        const VkImageSubresourceRange destinationRange = image_range_of(false);
        const VkImageSubresourceRange outputRange = image_range_of(false);
        const VkImageLayout destinationEntryLayout = current_layout_of(info->image);
        const VkImageLayout outputEntryLayout =
            current_layout_of(to_uint64(g_state.outputImage.image));

        // This transition is also what orders the copy behind the evaluation that wrote the
        // output image: it is a full memory dependency, and it always emits, because the layout
        // DLSS leaves the output in and TRANSFER_SRC_OPTIMAL cannot be the same layout. The
        // motion pass, whose entry and exit layouts *can* coincide, owns an explicit barrier
        // for exactly the case this one cannot reach.
        record_layout_transition(recordingBuffer, g_state.outputImage.image, outputRange,
                                 outputEntryLayout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        note_layout_after_transition(to_uint64(g_state.outputImage.image),
                                     VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        record_layout_transition(recordingBuffer, destination, destinationRange,
                                 destinationEntryLayout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

        // One mip, one layer, same extent: the destination is the engine's output-sized target
        // and the output image was allocated at exactly those dimensions, so this is a copy
        // rather than a scale. A blit would silently accept a mismatch this call rejects.
        VkImageCopy region{};
        region.srcSubresource = VkImageSubresourceLayers{VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        region.dstSubresource = VkImageSubresourceLayers{VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        region.extent = VkExtent3D{info->width, info->height, 1};
        vkCmdCopyImage(recordingBuffer, g_state.outputImage.image,
                       VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, destination,
                       VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);

        // Both images go back where their owners expect them, in the same recording: the
        // engine's target to the layout it arrived in, and the output image to the layout the
        // next evaluation's transitions will start from.
        record_layout_transition(recordingBuffer, destination, destinationRange,
                                 VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, destinationEntryLayout);
        record_layout_transition(recordingBuffer, g_state.outputImage.image, outputRange,
                                 VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, outputEntryLayout);
        note_layout_after_transition(to_uint64(g_state.outputImage.image), outputEntryLayout);
        mark_frame_timing(recordingBuffer, 3);
        return kSuccess;
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_reset(void) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_state.bootstrapComplete) {
            return kNotInitialized;
        }
        // The images belong to the feature's configuration, so a reset that drops the
        // feature drops them with it rather than leaving orphans the next acquire
        // would have to recognise.
        release_images();
        return release_feature();
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_close(void) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_state.initialized && g_state.capabilityParameters == nullptr) {
            return kSuccess;
        }
        return shutdown_state();
    } catch (...) {
        return kFailure;
    }
}
