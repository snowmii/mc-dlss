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
#include "internal/sl_dlss.h"
#include "internal/state.h"
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
constexpr char kEngineVersion[] = "Minecraft 26.2";
constexpr sl::Feature kStreamlineFeatures[] = {sl::kFeatureDLSS, sl::kFeatureDLSS_G, sl::kFeatureReflex};

// The manual-hook Vulkan preferences this module always uses, shared by every bootstrap (a
// close's slShutdown tears the runtime down and reset_state forgets the bootstrap, so the
// next mc_dlss_bootstrap_streamline runs slInit again). `paths` is the caller-owned one-element
// array the pointer must stay valid for the duration of slInit.
sl::Preferences make_streamline_preferences(const std::wstring& plugin_path, const wchar_t** paths) {
    paths[0] = plugin_path.c_str();
    sl::Preferences preferences{};
    preferences.pathsToPlugins = paths;
    preferences.numPathsToPlugins = 1;
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

// Whether slInit's answer means the runtime is up: eOk, or the two errors it reports when the
// plugin manager is already initialized by a previous bootstrap (the SL runtime is loaded for
// the whole process while this module is pinned for the JVM lifetime).
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
        if (slGetFeatureRequirements(feature, requirements) != sl::Result::eOk) return kFailure;
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

// The Vulkan 1.2/1.3 feature names the loaded features require, deduplicated across features
// and copied through the same index/name/capacity/count copier as the extension queries.
int32_t collect_streamline_feature_names(const bool features13, const uint32_t index, char* name,
                                         const uint32_t nameCapacity, uint32_t* count) {
    if (!g_state.streamlineInitialized) return kNotInitialized;
    std::vector<const char*> names;
    for (const sl::Feature feature : kStreamlineFeatures) {
        sl::FeatureRequirements requirements{};
        if (slGetFeatureRequirements(feature, requirements) != sl::Result::eOk) return kFailure;
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

// The extra graphics/compute/optical-flow queue counts the loaded features require, summed
// across features. The host is expected to create these queues itself before the device
// exists; optical flow is reported but the host may skip it (DLSS-G falls back to interop).
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
        if (slGetFeatureRequirements(feature, requirements) != sl::Result::eOk) return kFailure;
        graphics += requirements.vkNumGraphicsQueuesRequired;
        compute += requirements.vkNumComputeQueuesRequired;
        opticalFlow += requirements.vkNumOpticalFlowQueuesRequired;
    }
    *extra_graphics_queues = graphics;
    *extra_compute_queues = compute;
    *extra_optical_flow_queues = opticalFlow;
    return kSuccess;
}

// The recording calls all name the render dimensions they believe are configured. The value is
// never used as a size - everything is sized from the configuration - so this is purely the
// check that the caller has not lost track of what it configured.
bool matches_configured_render_size(const uint32_t width, const uint32_t height) noexcept {
    return width != 0 && height != 0 && width == g_state.renderWidth &&
           height == g_state.renderHeight;
}

} // namespace

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_bootstrap_streamline(const char* plugin_path) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_state.streamlineInitialized) return kSuccess;
        std::wstring pluginPath;
        if (!utf8_to_wide(plugin_path, pluginPath)) return kInvalidParameter;
        const wchar_t* paths[1] = {pluginPath.c_str()};
        const sl::Preferences preferences = make_streamline_preferences(pluginPath, paths);
        const sl::Result slInitResult = slInit(preferences);
        // The module is pinned for the JVM lifetime (one native-library lookup per path,
        // Arena.global), so a fresh bridge shares the module instance an earlier bridge
        // bootstrapped. The two errors slInit reports in that state both mean the plugin
        // manager is already up, and every query below works against it. Anything else - a
        // missing plugin, a bad path - still fails loudly.
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
            // A different device cannot replace the layout Streamline already hooks while the
            // session is live: the SL runtime accepts one device per process, and the caller
            // must not be silently re-hooking around it. The path to a fresh device is close,
            // which shuts Streamline down while the old device is still alive and resets the
            // proxy tuple, after which a new bootstrap and activation record the new tuple.
            return kInvalidParameter;
        }
        sl::VulkanInfo info{};
        info.device = from_uint64<VkDevice>(vk_device);
        info.instance = from_uint64<VkInstance>(vk_instance);
        info.physicalDevice = from_uint64<VkPhysicalDevice>(vk_physical_device);
        // Streamline's queue indices are the indices at which its own queues start, so the
        // host passes its queue COUNT in each family - the number of queues the host created
        // for itself, after which Streamline's extra queues follow. No optical-flow family is
        // recorded because the host creates none of its own (DLSS-G then runs in interop mode).
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
        // The pre-creation requirements come from Streamline and nowhere else: the direct-NGX
        // discovery fallback is retired, so a query before bootstrap fails rather than
        // answering from a runtime that is no longer part of the stack.
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
        // sdk_path and data_path are compatibility inputs: the retired direct-NGX path used
        // them to locate the feature DLL and its data, and nothing in the Streamline stack
        // consumes them. They are still validated as well-formed paths and nothing else.
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
                // A different device cannot replace the recorded tuple without close.
                return kInvalidParameter;
            }
            return kSuccess;
        }

        // The tuple this module records is the live one the mod already handed to
        // slSetVulkanInfo at proxy activation: the module-owned images and motion pass
        // allocate against it, and a session that never activated - or one whose recorded
        // tuple disagrees - must fail rather than record a device Streamline knows nothing
        // about. The module is pinned for the JVM lifetime, so the bootstrap state and the
        // proxy tuple recorded by the query and activation bridges survive their closes and
        // are exactly what this check requires.
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
        // Streamline answers the optimal-settings query and nothing else answers it: the
        // direct-NGX optimal-settings callback is retired with the rest of the NGX path.
        return query_optimal_dimensions_sl(output_width, output_height, quality_mode,
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
        // The SL session gate lives inside record_sr_options: configuring against a bootstrap
        // without a recorded device stores nothing the recording calls could use and answers
        // FAIL_NotInitialized, exactly where the retired direct-NGX ready gate used to sit.
        return record_sr_options();
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
        if (!g_state.sessionReady) {
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
        // The SL session is the only evaluation path now: the direct-NGX feature lifecycle
        // retired with it, and there is no fallback.
        if (!sl_session_ready()) {
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

        const int32_t evaluateResult = record_sr_evaluation(*info, recordingBuffer);

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
        if (!g_state.sessionReady) {
            return kNotInitialized;
        }
        // The images belong to the configuration, so a reset drops them with it rather than
        // leaving orphans the next acquire would have to recognise. The retained Streamline
        // frame token belongs to a frame that will never evaluate, so it goes too: the next
        // tag must obtain a fresh token rather than advance the frame under a stale one.
        release_images();
        g_state.frameToken = nullptr;
        return kSuccess;
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
