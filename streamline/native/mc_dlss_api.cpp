#include "mc_dlss.h"

#include <cstdlib>

#include "internal/common.h"
#include "internal/images.h"
#include "internal/motion.h"
#include "internal/ngx.h"
#include "internal/session.h"
#include "internal/sl_dlss.h"
#include "internal/state.h"
#include "internal/tonemap.h"
#include "internal/timing.h"

#include <sl.h>
#include <sl_dlss_g.h>
#include <sl_helpers_vk.h>
#include <sl_reflex.h>

#include <cstring>
#include <mutex>
#include <string>
#include <vector>

using namespace mc_dlss;

namespace {

constexpr char kProjectId[] = "50f68c51-c7be-49bd-a875-f73045f88d27";
constexpr char kEngineVersion[] = "Minecraft 26.3";
constexpr sl::Feature kStreamlineFeatures[] = {
	sl::kFeatureDLSS, sl::kFeatureDLSS_RR, sl::kFeatureDLSS_G, sl::kFeatureReflex, sl::kFeaturePCL};

sl::Preferences make_streamline_preferences(const std::wstring& plugin_path, const wchar_t** paths) {
    paths[0] = plugin_path.c_str();
    sl::Preferences preferences{};
    preferences.pathsToPlugins = paths;
    preferences.numPathsToPlugins = 1;
    if (std::getenv("MC_DLSS_SL_LOG") != nullptr) {
        preferences.logLevel = sl::LogLevel::eVerbose;
        preferences.pathToLogsAndData = plugin_path.c_str();
    } else {
        preferences.logLevel = sl::LogLevel::eOff;
        preferences.pathToLogsAndData = nullptr;
    }
    preferences.flags = sl::PreferenceFlags::eDisableCLStateTracking |
                        sl::PreferenceFlags::eUseManualHooking |
                        sl::PreferenceFlags::eUseFrameBasedResourceTagging;
    preferences.featuresToLoad = kStreamlineFeatures;
    preferences.numFeaturesToLoad = static_cast<uint32_t>(std::size(kStreamlineFeatures));
    preferences.engine = sl::EngineType::eCustom;
    preferences.engineVersion = kEngineVersion;
    preferences.projectId = kProjectId;
    preferences.renderAPI = sl::RenderAPI::eVulkan;
    return preferences;
}

bool streamline_initialized_after(const sl::Result slInitResult) {
    return slInitResult == sl::Result::eOk ||
           slInitResult == sl::Result::eErrorInitNotCalled ||
           slInitResult == sl::Result::eErrorInvalidState;
}

int32_t copy_streamline_extension(const uint32_t index, char* name,
                                  const uint32_t nameCapacity, uint32_t* count,
                                  const std::vector<const char*>& extensions) {
    if (count == nullptr) return kInvalidParameter;
    *count = static_cast<uint32_t>(extensions.size());
    if (name == nullptr && nameCapacity == 0) return kSuccess;
    if (index >= extensions.size() || name == nullptr || nameCapacity == 0) return kInvalidParameter;
    const size_t length = std::strlen(extensions[index]);
    if (length + 1 > nameCapacity) return kInvalidParameter;
    std::memcpy(name, extensions[index], length + 1);
    return kSuccess;
}

int32_t collect_streamline_extensions(const bool device, const uint32_t index, char* name,
                                      const uint32_t nameCapacity, uint32_t* count) {
    if (!g_state.streamlineInitialized) return kNotInitialized;
    std::vector<const char*> extensions;
    for (const sl::Feature feature : kStreamlineFeatures) {
        sl::FeatureRequirements requirements{};
        if (slGetFeatureRequirements(feature, requirements) != sl::Result::eOk) continue;
        const uint32_t size = device ? requirements.vkNumDeviceExtensions : requirements.vkNumInstanceExtensions;
        const char* const* values = device ? requirements.vkDeviceExtensions : requirements.vkInstanceExtensions;
        for (uint32_t i = 0; i < size; ++i) {
            bool duplicate = false;
            for (const char* existing : extensions) duplicate |= std::strcmp(existing, values[i]) == 0;
            if (!duplicate) extensions.push_back(values[i]);
        }
    }
    return copy_streamline_extension(index, name, nameCapacity, count, extensions);
}

int32_t collect_streamline_feature_names(const bool features13, const uint32_t index, char* name,
                                         const uint32_t nameCapacity, uint32_t* count) {
    if (!g_state.streamlineInitialized) return kNotInitialized;
    std::vector<const char*> names;
    for (const sl::Feature feature : kStreamlineFeatures) {
        sl::FeatureRequirements requirements{};
        if (slGetFeatureRequirements(feature, requirements) != sl::Result::eOk) continue;
        const uint32_t size = features13 ? requirements.vkNumFeatures13 : requirements.vkNumFeatures12;
        const char* const* values = features13 ? requirements.vkFeatures13 : requirements.vkFeatures12;
        for (uint32_t i = 0; i < size; ++i) {
            bool duplicate = false;
            for (const char* existing : names) duplicate |= std::strcmp(existing, values[i]) == 0;
            if (!duplicate) names.push_back(values[i]);
        }
    }
    return copy_streamline_extension(index, name, nameCapacity, count, names);
}

int32_t collect_streamline_queue_requirements(uint32_t* extra_graphics_queues,
                                              uint32_t* extra_compute_queues,
                                              uint32_t* extra_optical_flow_queues) {
    if (!g_state.streamlineInitialized) return kNotInitialized;
    if (extra_graphics_queues == nullptr || extra_compute_queues == nullptr ||
        extra_optical_flow_queues == nullptr) {
        return kInvalidParameter;
    }
    uint32_t graphics = 0;
    uint32_t compute = 0;
    uint32_t opticalFlow = 0;
    for (const sl::Feature feature : kStreamlineFeatures) {
        sl::FeatureRequirements requirements{};
        if (slGetFeatureRequirements(feature, requirements) != sl::Result::eOk) continue;
        graphics += requirements.vkNumGraphicsQueuesRequired;
        compute += requirements.vkNumComputeQueuesRequired;
        opticalFlow += requirements.vkNumOpticalFlowQueuesRequired;
    }
    *extra_graphics_queues = graphics;
    *extra_compute_queues = compute;
    *extra_optical_flow_queues = opticalFlow;
    return kSuccess;
}

bool matches_configured_render_size(const uint32_t width, const uint32_t height) noexcept {
    return width != 0 && height != 0 && width == g_state.renderWidth &&
           height == g_state.renderHeight;
}

}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_bootstrap_streamline(const char* plugin_path) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_state.streamlineInitialized) return kSuccess;
        std::wstring pluginPath;
        if (!utf8_to_wide(plugin_path, pluginPath)) return kInvalidParameter;
        const wchar_t* paths[1] = {pluginPath.c_str()};
        const sl::Preferences preferences = make_streamline_preferences(pluginPath, paths);
        const sl::Result slInitResult = slInit(preferences);
        if (!streamline_initialized_after(slInitResult)) return kFailure;
        g_state.streamlineInitialized = true;
        return kSuccess;
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_activate_vulkan_proxies(
    const uint64_t vk_instance, const uint64_t vk_physical_device, const uint64_t vk_device,
    const uint32_t graphics_queue_family, const uint32_t graphics_queue_index,
    const uint32_t compute_queue_family, const uint32_t compute_queue_index) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_state.streamlineInitialized) return kNotInitialized;
        if (vk_instance == 0 || vk_physical_device == 0 || vk_device == 0) {
            return kInvalidParameter;
        }
        if (g_state.proxyDevice != 0 && g_state.proxyInstance == vk_instance &&
            g_state.proxyPhysicalDevice == vk_physical_device &&
            g_state.proxyDevice == vk_device &&
            g_state.proxyGraphicsQueueFamily == graphics_queue_family &&
            g_state.proxyGraphicsQueueIndex == graphics_queue_index &&
            g_state.proxyComputeQueueFamily == compute_queue_family &&
            g_state.proxyComputeQueueIndex == compute_queue_index) {
            return kSuccess;
        }
        if (g_state.proxyDevice != 0) {
            return kInvalidParameter;
        }
        sl::VulkanInfo info{};
        info.device = from_uint64<VkDevice>(vk_device);
        info.instance = from_uint64<VkInstance>(vk_instance);
        info.physicalDevice = from_uint64<VkPhysicalDevice>(vk_physical_device);
        info.graphicsQueueFamily = graphics_queue_family;
        info.graphicsQueueIndex = graphics_queue_index;
        info.computeQueueFamily = compute_queue_family;
        info.computeQueueIndex = compute_queue_index;
        info.opticalFlowQueueIndex = 0;
        info.opticalFlowQueueFamily = 0;
        info.useNativeOpticalFlowMode = false;
        info.computeQueueCreateFlags = 0;
        info.graphicsQueueCreateFlags = 0;
        info.opticalFlowQueueCreateFlags = 0;
        if (slSetVulkanInfo(info) != sl::Result::eOk) return kFailure;
        g_state.proxyInstance = vk_instance;
        g_state.proxyPhysicalDevice = vk_physical_device;
        g_state.proxyDevice = vk_device;
        g_state.proxyGraphicsQueueFamily = graphics_queue_family;
        g_state.proxyGraphicsQueueIndex = graphics_queue_index;
        g_state.proxyComputeQueueFamily = compute_queue_family;
        g_state.proxyComputeQueueIndex = compute_queue_index;
        return kSuccess;
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_instance_extension(
    const uint32_t index, char* name, const uint32_t name_capacity,
    uint32_t* extension_count) {
    try {
        return collect_streamline_extensions(false, index, name, name_capacity,
                                             extension_count);
    } catch (...) { return kFailure; }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_device_extension(
    const uint64_t vk_instance, const uint64_t vk_physical_device,
    const uint32_t index, char* name, const uint32_t name_capacity,
    uint32_t* extension_count) {
    try {
        if (vk_instance == 0 || vk_physical_device == 0) return kInvalidParameter;
        return collect_streamline_extensions(true, index, name, name_capacity,
                                             extension_count);
    } catch (...) { return kFailure; }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_is_feature_supported(
    const uint32_t feature, const uint64_t vk_physical_device) {
    try {
        const std::lock_guard<std::mutex> lock(g_mutex);
        return is_feature_supported_sl(feature, vk_physical_device);
    } catch (...) { return kFailure; }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_device_feature_12(
    const uint32_t index, char* name, const uint32_t name_capacity,
    uint32_t* feature_count) {
    try {
        return collect_streamline_feature_names(false, index, name, name_capacity, feature_count);
    } catch (...) { return kFailure; }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_device_feature_13(
    const uint32_t index, char* name, const uint32_t name_capacity,
    uint32_t* feature_count) {
    try {
        return collect_streamline_feature_names(true, index, name, name_capacity, feature_count);
    } catch (...) { return kFailure; }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_queue_requirements(
    uint32_t* extra_graphics_queues, uint32_t* extra_compute_queues,
    uint32_t* extra_optical_flow_queues) {
    try {
        return collect_streamline_queue_requirements(extra_graphics_queues,
                                                     extra_compute_queues,
                                                     extra_optical_flow_queues);
    } catch (...) { return kFailure; }
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

        if (g_state.sessionReady) {
            const bool sameTuple = g_state.instanceValue == vk_instance &&
                                   g_state.physicalDeviceValue == vk_physical_device &&
                                   g_state.deviceValue == vk_device;
            if (!sameTuple) {
                return kInvalidParameter;
            }
            return kSuccess;
        }

        if (!g_state.streamlineInitialized) {
            return kNotInitialized;
        }
        if (g_state.proxyDevice == 0) {
            return kNotInitialized;
        }
        if (g_state.proxyInstance != vk_instance ||
            g_state.proxyPhysicalDevice != vk_physical_device ||
            g_state.proxyDevice != vk_device) {
            return kInvalidParameter;
        }

        g_state.instanceValue = vk_instance;
        g_state.physicalDeviceValue = vk_physical_device;
        g_state.deviceValue = vk_device;
        g_state.instance = from_uint64<VkInstance>(vk_instance);
        g_state.physicalDevice = from_uint64<VkPhysicalDevice>(vk_physical_device);
        g_state.device = from_uint64<VkDevice>(vk_device);
        g_state.sessionReady = true;
        record_reflex_options();
        return kSuccess;
    } catch (...) {
        std::lock_guard<std::mutex> lock(g_mutex);
        reset_state();
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_optimal_dimensions(
    const uint32_t output_width, const uint32_t output_height, const uint32_t quality_mode,
    uint32_t* render_width, uint32_t* render_height) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return query_optimal_dimensions_sl(output_width, output_height, quality_mode,
                                           render_width, render_height);
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_optimal_dimensions_rr(
    const uint32_t output_width, const uint32_t output_height, const uint32_t quality_mode,
    uint32_t* render_width, uint32_t* render_height) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return query_optimal_dimensions_rr_sl(output_width, output_height, quality_mode,
                                              render_width, render_height);
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
        g_state.fgOptions.recorded = false;
        g_state.frameEligibility.invalidate();
        return record_sr_options();
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_configure_fg(const uint32_t num_back_buffers) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return record_fg_options(num_back_buffers);
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_set_fg_mode(const uint32_t fg_enabled) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return record_fg_mode(fg_enabled);
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_set_fg_multiplier(const uint32_t num_frames_to_generate) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return record_fg_multiplier(num_frames_to_generate);
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_fg_multiplier(uint32_t* current,
                                                              uint32_t* max) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return query_fg_multiplier(current, max);
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_acquire_images(McDlssImage* motion,
                                                         McDlssImage* output) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_state.sessionReady) {
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
        g_state.frameEligibility.invalidate();
        return kSuccess;
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_fg_images(McDlssImage* depth,
                                                          McDlssImage* hudless,
                                                          McDlssImage* ui,
                                                          McDlssImage* motion) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_state.sessionReady) {
            return kNotInitialized;
        }
        if (depth == nullptr || hudless == nullptr || ui == nullptr || motion == nullptr) {
            return kInvalidParameter;
        }
        if (!images_match_configuration()) {
            return kNotInitialized;
        }
        depth->view = to_uint64(g_state.fgDepthImage.view);
        depth->image = to_uint64(g_state.fgDepthImage.image);
        depth->format = static_cast<uint32_t>(VK_FORMAT_D32_SFLOAT);
        hudless->view = to_uint64(g_state.fgHudlessImage.view);
        hudless->image = to_uint64(g_state.fgHudlessImage.image);
        hudless->format = static_cast<uint32_t>(kOutputFormat);
        ui->view = to_uint64(g_state.fgUiImage.view);
        ui->image = to_uint64(g_state.fgUiImage.image);
        ui->format = static_cast<uint32_t>(kOutputFormat);
        motion->view = to_uint64(g_state.fgMotionImage.view);
        motion->image = to_uint64(g_state.fgMotionImage.image);
        motion->format = static_cast<uint32_t>(kMotionFormat);
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

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_tagged_frame_indexes(
    uint32_t* sr_frame_index,
    uint32_t* fg_frame_index) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (sr_frame_index == nullptr || fg_frame_index == nullptr) {
            return kInvalidParameter;
        }
        if (!g_state.frameEligibility.tagIndexes(sr_frame_index, fg_frame_index)) {
            return kNotInitialized;
        }
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
        if (!g_state.sessionReady) {
            return kNotInitialized;
        }
        if (info == nullptr || info->command_buffer == 0 || info->reprojection == nullptr ||
            !matches_configured_render_size(info->render_width, info->render_height) ||
            !valid_image(info->depth)) {
            return kInvalidParameter;
        }
        if (!images_match_configuration()) {
            return kNotInitialized;
        }

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
        if (!sl_session_ready()) {
            return kNotInitialized;
        }
        if (info == nullptr || info->command_buffer == 0 || info->reset_history < 0 ||
            info->reset_history > 1 ||
            !matches_configured_render_size(info->render_width, info->render_height) ||
            !valid_image(info->color) || !valid_image(info->depth)) {
            return kInvalidParameter;
        }
        if (!images_match_configuration()) {
            return kNotInitialized;
        }

        const VkCommandBuffer recordingBuffer =
            from_uint64<VkCommandBuffer>(info->command_buffer);

        const uint64_t motionImage = to_uint64(g_state.motionImage.image);
        const uint64_t evaluationOutput = to_uint64(
            g_state.rrArmed ? g_state.rrOutputImage.image : g_state.outputImage.image);
        const VkImageLayout colorEntryLayout = current_layout_of(info->color.image);
        const VkImageLayout depthEntryLayout = current_layout_of(info->depth.image);

        constexpr VkPipelineStageFlags kEvalStages = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        constexpr VkAccessFlags kEvalAccess = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;

        ImageTransitionDesc preDescs[11]{};
        uint32_t preCount = 0;
        auto add_pre_transition = [&](const ImageTransitionDesc& desc) {
            if (desc.image == VK_NULL_HANDLE) return;
            for (uint32_t i = 0; i < preCount; ++i) {
                if (preDescs[i].image == desc.image) return;
            }
            preDescs[preCount++] = desc;
        };

        const VkImage colorImg = from_uint64<VkImage>(info->color.image);
        const VkImage depthImg = from_uint64<VkImage>(info->depth.image);
        const VkImage motionImg = g_state.motionImage.image;
        const VkImage outputImg = g_state.rrArmed ? g_state.rrOutputImage.image : g_state.outputImage.image;

        add_pre_transition({colorImg, image_range_of(false), colorEntryLayout, kDlssInputLayout,
                            kEvalStages, kEvalStages, kEvalAccess, kEvalAccess});
        add_pre_transition({depthImg, depth_range_of(info->depth), depthEntryLayout, kDlssInputLayout,
                            kEvalStages, kEvalStages, kEvalAccess, kEvalAccess});
        add_pre_transition({motionImg, image_range_of(false), current_layout_of(motionImage), kDlssInputLayout,
                            kEvalStages, kEvalStages, kEvalAccess, kEvalAccess});
        add_pre_transition({outputImg, image_range_of(false), current_layout_of(evaluationOutput), kDlssOutputLayout,
                            kEvalStages, kEvalStages, kEvalAccess, kEvalAccess});

        VkImageLayout rrAlbedoEntry = VK_IMAGE_LAYOUT_UNDEFINED;
        VkImageLayout rrSpecularAlbedoEntry = VK_IMAGE_LAYOUT_UNDEFINED;
        VkImageLayout rrNormalEntry = VK_IMAGE_LAYOUT_UNDEFINED;
        VkImageLayout rrHitDistanceEntry = VK_IMAGE_LAYOUT_UNDEFINED;
        VkImageLayout rrSpecularMotionEntry = VK_IMAGE_LAYOUT_UNDEFINED;
        const VkImageLayout rrBiasEntry = current_layout_of(g_state.rrBiasCurrentColor.image);
        if (g_state.rrArmed) {
            rrAlbedoEntry = current_layout_of(g_state.rrDiffuseAlbedo.image);
            rrSpecularAlbedoEntry = current_layout_of(g_state.rrSpecularAlbedo.image);
            rrNormalEntry = current_layout_of(g_state.rrNormalRoughness.image);
            add_pre_transition({from_uint64<VkImage>(g_state.rrDiffuseAlbedo.image),
                                image_range_of(false), rrAlbedoEntry, kDlssInputLayout,
                                kEvalStages, kEvalStages, kEvalAccess, kEvalAccess});
            add_pre_transition({from_uint64<VkImage>(g_state.rrSpecularAlbedo.image),
                                image_range_of(false), rrSpecularAlbedoEntry, kDlssInputLayout,
                                kEvalStages, kEvalStages, kEvalAccess, kEvalAccess});
            add_pre_transition({from_uint64<VkImage>(g_state.rrNormalRoughness.image),
                                image_range_of(false), rrNormalEntry, kDlssInputLayout,
                                kEvalStages, kEvalStages, kEvalAccess, kEvalAccess});
            if (valid_image(g_state.rrSpecularMotion) && g_state.rrSpecularMotion.image != 0) {
                rrSpecularMotionEntry = current_layout_of(g_state.rrSpecularMotion.image);
                add_pre_transition({from_uint64<VkImage>(g_state.rrSpecularMotion.image),
                                    image_range_of(false), rrSpecularMotionEntry, kDlssInputLayout,
                                    kEvalStages, kEvalStages, kEvalAccess, kEvalAccess});
            } else if (valid_image(g_state.rrSpecularHitDistance) && g_state.rrSpecularHitDistance.image != 0) {
                rrHitDistanceEntry = current_layout_of(g_state.rrSpecularHitDistance.image);
                add_pre_transition({from_uint64<VkImage>(g_state.rrSpecularHitDistance.image),
                                    image_range_of(false), rrHitDistanceEntry, kDlssInputLayout,
                                    kEvalStages, kEvalStages, kEvalAccess, kEvalAccess});
            }
        }

        add_pre_transition(rr_bias_current_color_transition(rrBiasEntry, false));
        record_batched_transitions(recordingBuffer, preDescs, preCount);
        note_layout_after_transition(motionImage, kDlssInputLayout);
        note_layout_after_transition(evaluationOutput, kDlssOutputLayout);

        const int32_t evaluateResult = record_sr_evaluation(*info, recordingBuffer);

        ImageTransitionDesc postDescs[9]{};
        uint32_t postCount = 0;
        auto add_post_transition = [&](const ImageTransitionDesc& desc) {
            if (desc.image == VK_NULL_HANDLE) return;
            for (uint32_t i = 0; i < postCount; ++i) {
                if (postDescs[i].image == desc.image) return;
            }
            postDescs[postCount++] = desc;
        };

        add_post_transition({colorImg, image_range_of(false), kDlssInputLayout, colorEntryLayout,
                             kEvalStages, kEvalStages, kEvalAccess, kEvalAccess});
        add_post_transition({depthImg, depth_range_of(info->depth), kDlssInputLayout, depthEntryLayout,
                             kEvalStages, kEvalStages, kEvalAccess, kEvalAccess});
        if (g_state.rrArmed) {
            add_post_transition({from_uint64<VkImage>(g_state.rrDiffuseAlbedo.image),
                                 image_range_of(false), kDlssInputLayout, rrAlbedoEntry,
                                 kEvalStages, kEvalStages, kEvalAccess, kEvalAccess});
            add_post_transition({from_uint64<VkImage>(g_state.rrSpecularAlbedo.image),
                                 image_range_of(false), kDlssInputLayout, rrSpecularAlbedoEntry,
                                 kEvalStages, kEvalStages, kEvalAccess, kEvalAccess});
            add_post_transition({from_uint64<VkImage>(g_state.rrNormalRoughness.image),
                                 image_range_of(false), kDlssInputLayout, rrNormalEntry,
                                 kEvalStages, kEvalStages, kEvalAccess, kEvalAccess});
            if (valid_image(g_state.rrSpecularMotion) && g_state.rrSpecularMotion.image != 0) {
                add_post_transition({from_uint64<VkImage>(g_state.rrSpecularMotion.image),
                                     image_range_of(false), kDlssInputLayout, rrSpecularMotionEntry,
                                     kEvalStages, kEvalStages, kEvalAccess, kEvalAccess});
            } else if (valid_image(g_state.rrSpecularHitDistance) && g_state.rrSpecularHitDistance.image != 0) {
                add_post_transition({from_uint64<VkImage>(g_state.rrSpecularHitDistance.image),
                                     image_range_of(false), kDlssInputLayout, rrHitDistanceEntry,
                                     kEvalStages, kEvalStages, kEvalAccess, kEvalAccess});
            }
        }
        add_post_transition(rr_bias_current_color_transition(rrBiasEntry, true));
        record_batched_transitions(recordingBuffer, postDescs, postCount);
        mark_frame_timing(recordingBuffer, 2);
        return evaluateResult;
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_tag_sr_resources(const McDlssTagInfo* info) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (info == nullptr) {
            return kInvalidParameter;
        }
        return tag_sr_resources(*info);
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_prepare_rr(const McDlssRrPrepareInfo* info) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (info == nullptr) {
            return kInvalidParameter;
        }
        return prepare_rr(*info);
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_tag_fg_resources(const McDlssFgTagInfo* info) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (info == nullptr) {
            return kInvalidParameter;
        }
        return tag_fg_resources(*info);
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_present_handoff(void) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return record_present_handoff();
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_present_start(void) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return present_start();
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_present_end(void) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return present_end();
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_present_markers(
    uint32_t* start_count,
    uint32_t* end_count,
    uint32_t* event_count,
    uint32_t* events,
    uint32_t events_capacity) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return query_present_markers(start_count, end_count, event_count, events,
                                     events_capacity);
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_install_pcl_window(const uint64_t hwnd) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return install_pcl_window(hwnd);
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_reflex_input_sample(void) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return reflex_input_sample();
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_reflex_marker(const uint32_t marker_type) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return reflex_marker(marker_type);
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_reflex_markers(
    uint32_t* type_counts,
    uint32_t* event_count,
    uint32_t* events,
    uint32_t events_capacity) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return query_reflex_markers(type_counts, event_count, events, events_capacity);
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_record_reflex_frame_limit(const uint32_t frame_limit_us) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return record_reflex_frame_limit(frame_limit_us);
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_reflex_options(uint32_t* mode,
                                                               uint32_t* set_options_calls) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return query_reflex_options(mode, set_options_calls);
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_set_reflex_mode(const uint32_t mode) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return record_reflex_mode(mode);
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_reflex_state(
    uint32_t* low_latency_available,
    uint32_t* latency_report_available) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return query_reflex_state(low_latency_available, latency_report_available);
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_camera_constants(McDlssCameraConstants* out) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return query_camera_constants(out);
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_fg_camera_constants(McDlssCameraConstants* out) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return query_fg_camera_constants(out);
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_wait_fg_inputs_idle(void) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return wait_fg_inputs_idle();
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_wait_fg_inputs_value(uint64_t vk_device,
                                                               uint64_t semaphore,
                                                               uint64_t value) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return wait_fg_inputs_value(vk_device, semaphore, value);
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_fg_state(
    uint32_t* status, uint32_t* num_frames_presented,
    uint64_t* last_present_inputs_processing_fence_value,
    uint64_t* inputs_processing_completion_fence) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return query_fg_state(status, num_frames_presented,
                              last_present_inputs_processing_fence_value,
                              inputs_processing_completion_fence);
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_fg_vsync_support(uint32_t* vsync_supported) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return query_fg_vsync_support(vsync_supported);
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_present_output(const McDlssPresentInfo* info) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_state.sessionReady) {
            return kNotInitialized;
        }
        if (info == nullptr || info->command_buffer == 0 || info->image == 0 ||
            info->width != g_state.outputWidth || info->height != g_state.outputHeight) {
            return kInvalidParameter;
        }
        if (!images_match_configuration() ||
            g_state.imagesOutputWidth != info->width ||
            g_state.imagesOutputHeight != info->height) {
            return kNotInitialized;
        }
        if (info->image == to_uint64(g_state.outputImage.image) ||
            info->image == to_uint64(g_state.rrOutputImage.image)) {
            return kInvalidParameter;
        }

        const VkCommandBuffer recordingBuffer =
            from_uint64<VkCommandBuffer>(info->command_buffer);
        if (g_state.rrArmed) {
            const bool direct = info->image_view != 0 &&
                info->image_format == static_cast<uint32_t>(VK_FORMAT_R8G8B8A8_UNORM) &&
                (info->image_usage & VK_IMAGE_USAGE_STORAGE_BIT) != 0 &&
                info->image != info->reflection.image;
            const McDlssImage toneMapOutput = direct
                ? McDlssImage{info->image_view, info->image, info->image_format}
                : McDlssImage{to_uint64(g_state.outputImage.view), to_uint64(g_state.outputImage.image),
                              static_cast<uint32_t>(kOutputFormat)};
            const int32_t toneMapResult = record_tone_map(recordingBuffer, g_state.exposure,
                                                         info->reflection, toneMapOutput);
            if (toneMapResult != kSuccess) {
                return toneMapResult;
            }
            if (direct) {
                mark_frame_timing(recordingBuffer, 3);
                return kSuccess;
            }
        }
        const VkImage destination = from_uint64<VkImage>(info->image);
        const VkImageSubresourceRange destinationRange = image_range_of(false);
        const VkImageSubresourceRange outputRange = image_range_of(false);
        const VkImageLayout destinationEntryLayout = current_layout_of(info->image);
        const VkImageLayout outputEntryLayout =
            current_layout_of(to_uint64(g_state.outputImage.image));

        const ImageTransitionDesc preCopy[2] = {
            {g_state.outputImage.image, outputRange, outputEntryLayout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
             VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT},
            {destination, destinationRange, destinationEntryLayout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
             VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
             VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_WRITE_BIT}
        };
        record_batched_transitions(recordingBuffer, preCopy, 2);
        note_layout_after_transition(to_uint64(g_state.outputImage.image),
                                     VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

        VkImageCopy region{};
        region.srcSubresource = VkImageSubresourceLayers{VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        region.dstSubresource = VkImageSubresourceLayers{VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        region.extent = VkExtent3D{info->width, info->height, 1};
        vkCmdCopyImage(recordingBuffer, g_state.outputImage.image,
                       VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, destination,
                       VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);

        const ImageTransitionDesc postCopy[2] = {
            {destination, destinationRange, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, destinationEntryLayout,
             VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
             VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT},
            {g_state.outputImage.image, outputRange, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, outputEntryLayout,
             VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
             VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT}
        };
        record_batched_transitions(recordingBuffer, postCopy, 2);
        note_layout_after_transition(to_uint64(g_state.outputImage.image), outputEntryLayout);
        mark_frame_timing(recordingBuffer, 3);
        return kSuccess;
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_query_motion_probe(
    float* motion_x, float* motion_y, float* depth, int32_t* slot) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return query_motion_probe(motion_x, motion_y, depth, slot);
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_set_exposure(const float exposure) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        const float newExposure = exposure > 0.0f ? exposure : 1.0f;
        if (g_state.exposure != newExposure) {
            g_state.exposure = newExposure;
            if (g_state.rrArmed) {
                record_rr_options();
            }
        }
        return kSuccess;
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_set_tonemap_operator(const int32_t op) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_state.toneMapOperator = op >= 0 ? op : 0;
        return kSuccess;
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_reset(void) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_state.sessionReady) {
            return kNotInitialized;
        }
        release_images();
        g_state.frameEligibility.invalidate();
        return kSuccess;
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_shutdown_streamline(void) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return shutdown_state();
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_close(void) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_state.sessionReady) {
            return kSuccess;
        }
        return shutdown_state();
    } catch (...) {
        return kFailure;
    }
}
