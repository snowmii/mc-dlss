#include "mc_dlss.h"

#include <vulkan/vulkan.h>

#include <nvsdk_ngx.h>
#include <nvsdk_ngx_helpers_vk.h>
#include <nvsdk_ngx_vk.h>

#include <cstdint>
#include <cstring>
#include <limits>
#include <mutex>
#include <string>
#include <type_traits>
#include <utility>

#if defined(_WIN32)
#include <windows.h>
#else
#include <codecvt>
#include <locale>
#endif

namespace {

constexpr char kProjectId[] = "50f68c51-c7be-49bd-a875-f73045f88d27";
constexpr char kEngineVersion[] = "Minecraft 26.2";
constexpr int32_t kSuccess = static_cast<int32_t>(NVSDK_NGX_Result_Success);
constexpr int32_t kFailure = static_cast<int32_t>(NVSDK_NGX_Result_Fail);
constexpr int32_t kInvalidParameter = static_cast<int32_t>(NVSDK_NGX_Result_FAIL_InvalidParameter);
constexpr int32_t kNotInitialized = static_cast<int32_t>(NVSDK_NGX_Result_FAIL_NotInitialized);
constexpr int32_t kFeatureNotSupported = static_cast<int32_t>(NVSDK_NGX_Result_FAIL_FeatureNotSupported);

// R16G16_SFLOAT and R8G8B8A8_UNORM are both mandatory storage-image formats in
// Vulkan, so neither needs a runtime capability probe. Two half-float channels
// carry a screen-space motion vector at full precision, and the output matches
// Minecraft's RGBA8_UNORM main target so the copy back into it is a plain image
// copy rather than a conversion.
constexpr VkFormat kMotionFormat = VK_FORMAT_R16G16_SFLOAT;
constexpr VkFormat kOutputFormat = VK_FORMAT_R8G8B8A8_UNORM;

// Minecraft 26.2 rests every GpuTexture in VK_IMAGE_LAYOUT_GENERAL: VulkanGpuTexture
// transitions the freshly created image straight to it, VulkanCommandEncoder binds colour
// and depth attachments at it, and VulkanRenderPass binds sampled images at it. Nothing in
// the backend ever moves a texture anywhere else, so this is the layout the engine's colour
// and depth images arrive in and the one they must be handed back in.
constexpr VkImageLayout kEngineRestingLayout = VK_IMAGE_LAYOUT_GENERAL;
// DLSS requires its inputs in a read state ("Sample Image") and its output in a storage
// state, per the 310.7.0 programming guide's Resource States section, and restores both
// after the evaluation.
constexpr VkImageLayout kDlssInputLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
constexpr VkImageLayout kDlssOutputLayout = VK_IMAGE_LAYOUT_GENERAL;

// Motion-vector compute pass. The workgroup size is duplicated from mc_dlss_motion.comp
// because the dispatch has to round the render size up to whole workgroups; the shader's
// own bounds check is what keeps the surplus invocations harmless.
constexpr uint32_t kMotionWorkgroupSize = 8;
// The pass writes its descriptor set only when the depth or motion view actually changes,
// which happens when Minecraft recreates its scene target and never per frame. A small ring
// means such a rewrite lands on a set no in-flight submission is still reading, without the
// pass having to track frame completion it has no way to observe.
constexpr uint32_t kMotionDescriptorRing = 4;
constexpr uint32_t kMotionSpirV[] =
#include "mc_dlss_motion.spv.h"
    ;

struct DlssOwnedImage {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    // Layout the last recorded barrier left this image in. Fresh allocations are
    // VK_IMAGE_LAYOUT_UNDEFINED until the first evaluation transitions them.
    VkImageLayout layout = VK_IMAGE_LAYOUT_UNDEFINED;
};

// Everything the motion dispatch needs, created once against the device and reused for the
// life of the session. Nothing here is per frame: a frame supplies only the command buffer,
// the depth resource, and the push constants.
struct DlssMotionPass {
    VkShaderModule shader = VK_NULL_HANDLE;
    VkSampler sampler = VK_NULL_HANDLE;
    VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet sets[kMotionDescriptorRing] = {};
    uint32_t nextSet = 0;
    // Which ring slot currently describes boundDepthView/boundMotionView, or -1 when no set
    // has been written yet.
    int32_t boundSet = -1;
    uint64_t boundDepthView = 0;
    uint64_t boundMotionView = 0;
};

// Push-constant block, matching mc_dlss_motion.comp exactly: a column-major mat4 followed by
// the render size.
struct DlssMotionPushConstants {
    float reprojection[16];
    int32_t renderWidth;
    int32_t renderHeight;
};

struct DlssState {
    bool initialized = false;
    bool bootstrapComplete = false;
    uint64_t instanceValue = 0;
    uint64_t physicalDeviceValue = 0;
    uint64_t deviceValue = 0;
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    std::wstring sdkPath;
    std::wstring dataPath;
    NVSDK_NGX_Parameter* capabilityParameters = nullptr;
    NVSDK_NGX_Handle* feature = nullptr;
    uint32_t outputWidth = 0;
    uint32_t outputHeight = 0;
    uint32_t renderWidth = 0;
    uint32_t renderHeight = 0;
    uint32_t qualityMode = 0;
    uint32_t renderPreset = 0;
    uint32_t featureOutputWidth = 0;
    uint32_t featureOutputHeight = 0;
    uint32_t featureRenderWidth = 0;
    uint32_t featureRenderHeight = 0;
    uint32_t featureQualityMode = 0;
    uint32_t featureRenderPreset = 0;
    DlssOwnedImage motionImage;
    DlssOwnedImage outputImage;
    DlssMotionPass motionPass;
    uint32_t imagesRenderWidth = 0;
    uint32_t imagesRenderHeight = 0;
    uint32_t imagesOutputWidth = 0;
    uint32_t imagesOutputHeight = 0;
};

DlssState g_state;
std::mutex g_mutex;

/*
 * GPU-side timing of the three stages this module records.
 *
 * Frame rate cannot answer where the time goes: a client whose frame length is set by the CPU
 * shows the same rate whether the DLSS chain costs 0.2ms or 2ms, and the GPU utilization that
 * does move is a ratio against a wall clock the renderer chose. Timestamps are the only thing
 * that separates NGX's own cost from the copy and the barriers around it.
 *
 * Four stamps per frame - one before the motion pass, one after it, one after the evaluation,
 * one after the copy - into a ring of slots, so results are read for a frame the GPU finished
 * several frames ago and no read ever waits. A frame that skips a stage leaves its slot
 * incomplete and is dropped rather than reported as a fast one.
 */
constexpr uint32_t kTimingSlotCount = 4;
constexpr uint32_t kTimingStampsPerSlot = 4;

struct DlssFrameTiming {
    VkQueryPool pool = VK_NULL_HANDLE;
    bool supported = true;
    float timestampPeriod = 0.0f;
    uint32_t recordingSlot = 0;
    uint32_t nextSlot = 0;
    /** Stamps written into the recording slot so far, which is also the next stamp's index. */
    uint32_t writtenStamps = 0;
    bool pending[kTimingSlotCount] = {};
    bool hasResult = false;
    float motionMs = 0.0f;
    float evaluateMs = 0.0f;
    float presentMs = 0.0f;
    float totalMs = 0.0f;
};

DlssFrameTiming g_timing;

template <typename VulkanHandle>
VulkanHandle from_uint64(const uint64_t value) noexcept {
    if constexpr (std::is_pointer<VulkanHandle>::value) {
        return reinterpret_cast<VulkanHandle>(static_cast<std::uintptr_t>(value));
    } else {
        return static_cast<VulkanHandle>(value);
    }
}

template <typename VulkanHandle>
uint64_t to_uint64(const VulkanHandle handle) noexcept {
    if constexpr (std::is_pointer<VulkanHandle>::value) {
        return static_cast<uint64_t>(reinterpret_cast<std::uintptr_t>(handle));
    } else {
        return static_cast<uint64_t>(handle);
    }
}

bool utf8_to_wide(const char* input, std::wstring& output) {
    if (input == nullptr || input[0] == '\0') {
        return false;
    }
#if defined(_WIN32)
    const size_t size = std::strlen(input);
    if (size > static_cast<size_t>(std::numeric_limits<int>::max())) {
        return false;
    }
    const int required = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, input,
                                              static_cast<int>(size), nullptr, 0);
    if (required <= 0) {
        return false;
    }
    output.resize(static_cast<size_t>(required));
    return MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, input,
                               static_cast<int>(size), output.data(), required) == required;
#else
    try {
        output = std::wstring_convert<std::codecvt_utf8<wchar_t>>().from_bytes(input);
        return !output.empty();
    } catch (...) {
        return false;
    }
#endif
}

bool valid_quality_mode(const uint32_t qualityMode) noexcept {
    switch (static_cast<NVSDK_NGX_PerfQuality_Value>(qualityMode)) {
        case NVSDK_NGX_PerfQuality_Value_MaxPerf:
        case NVSDK_NGX_PerfQuality_Value_Balanced:
        case NVSDK_NGX_PerfQuality_Value_MaxQuality:
        case NVSDK_NGX_PerfQuality_Value_UltraPerformance:
        case NVSDK_NGX_PerfQuality_Value_DLAA:
            return true;
        // UltraQuality sits between the two the SDK defines and never shipped a model.
        default:
            return false;
    }
}

/**
 * The capability parameter naming the preset for one quality mode.
 *
 * NGX keys the hint by mode rather than taking one preset, so the value only reaches the model
 * that is about to run if it is written under the mode's own name.
 */
const char* preset_parameter_for(const uint32_t qualityMode) noexcept {
    switch (static_cast<NVSDK_NGX_PerfQuality_Value>(qualityMode)) {
        case NVSDK_NGX_PerfQuality_Value_MaxPerf:
            return NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Performance;
        case NVSDK_NGX_PerfQuality_Value_Balanced:
            return NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Balanced;
        case NVSDK_NGX_PerfQuality_Value_MaxQuality:
            return NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Quality;
        case NVSDK_NGX_PerfQuality_Value_UltraPerformance:
            return NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraPerformance;
        case NVSDK_NGX_PerfQuality_Value_DLAA:
            return NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_DLAA;
        default:
            return nullptr;
    }
}

/**
 * Every preset SDK 310.7.0 documents as usable.
 *
 * The removed, deprecated, and reserved values are refused rather than forwarded: NGX answers
 * them by silently reverting to its own default, which is the one outcome an explicitly chosen
 * preset exists to prevent.
 */
bool valid_render_preset(const uint32_t renderPreset) noexcept {
    switch (static_cast<NVSDK_NGX_DLSS_Hint_Render_Preset>(renderPreset)) {
        case NVSDK_NGX_DLSS_Hint_Render_Preset_J:
        case NVSDK_NGX_DLSS_Hint_Render_Preset_K:
        case NVSDK_NGX_DLSS_Hint_Render_Preset_L:
        case NVSDK_NGX_DLSS_Hint_Render_Preset_M:
            return true;
        default:
            return false;
    }
}

bool valid_dimensions(const uint32_t outputWidth, const uint32_t outputHeight,
                      const uint32_t renderWidth, const uint32_t renderHeight) noexcept {
    return outputWidth != 0 && outputHeight != 0 && renderWidth != 0 && renderHeight != 0 &&
           renderWidth <= outputWidth && renderHeight <= outputHeight;
}

void reset_state() noexcept {
    g_state = DlssState{};
}

// Destroying a resource the GPU may still be reading is the one Vulkan error nothing
// reports where it happens: the queued command buffers that reference it keep running,
// the driver loses the device some frames later, and the crash surfaces in whatever
// unrelated call waits on a semaphore next. Every destroy path here stalls first, and
// the stall is affordable because none of them runs per frame - they run when the
// configuration changes or the session ends.
void wait_device_idle() noexcept {
    if (g_state.device != VK_NULL_HANDLE) {
        // Result deliberately ignored: on a device already lost there is nothing left to
        // wait for and nothing left to salvage, and the destroys still have to happen.
        vkDeviceWaitIdle(g_state.device);
    }
}

// Creates the query pool on first use, or gives up permanently on a device that cannot
// timestamp graphics work. Timing is diagnostic, so every failure here disables it rather
// than failing the frame that asked for it.
bool ensure_timing_pool() noexcept {
    if (!g_timing.supported) {
        return false;
    }
    if (g_timing.pool != VK_NULL_HANDLE) {
        return true;
    }
    if (g_state.device == VK_NULL_HANDLE || g_state.physicalDevice == VK_NULL_HANDLE) {
        return false;
    }

    VkPhysicalDeviceProperties properties{};
    vkGetPhysicalDeviceProperties(g_state.physicalDevice, &properties);
    if (properties.limits.timestampComputeAndGraphics == VK_FALSE ||
        properties.limits.timestampPeriod == 0.0f) {
        g_timing.supported = false;
        return false;
    }

    VkQueryPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
    poolInfo.queryType = VK_QUERY_TYPE_TIMESTAMP;
    poolInfo.queryCount = kTimingSlotCount * kTimingStampsPerSlot;
    VkQueryPool pool = VK_NULL_HANDLE;
    if (vkCreateQueryPool(g_state.device, &poolInfo, nullptr, &pool) != VK_SUCCESS) {
        g_timing.supported = false;
        return false;
    }

    g_timing.pool = pool;
    g_timing.timestampPeriod = properties.limits.timestampPeriod;
    return true;
}

// Reads one slot's stamps if the GPU is done with them, and never waits: an unfinished slot
// is left for the next pass around the ring rather than stalling the frame being recorded.
void collect_timing(const uint32_t slot) noexcept {
    if (!g_timing.pending[slot] || g_timing.pool == VK_NULL_HANDLE) {
        return;
    }

    uint64_t stamps[kTimingStampsPerSlot]{};
    const VkResult result = vkGetQueryPoolResults(
        g_state.device, g_timing.pool, slot * kTimingStampsPerSlot, kTimingStampsPerSlot,
        sizeof(stamps), stamps, sizeof(uint64_t), VK_QUERY_RESULT_64_BIT);
    if (result != VK_SUCCESS) {
        return;
    }

    const float toMilliseconds = g_timing.timestampPeriod / 1000000.0f;
    g_timing.motionMs = static_cast<float>(stamps[1] - stamps[0]) * toMilliseconds;
    g_timing.evaluateMs = static_cast<float>(stamps[2] - stamps[1]) * toMilliseconds;
    g_timing.presentMs = static_cast<float>(stamps[3] - stamps[2]) * toMilliseconds;
    g_timing.totalMs = static_cast<float>(stamps[3] - stamps[0]) * toMilliseconds;
    g_timing.hasResult = true;
    g_timing.pending[slot] = false;
}

// Opens a slot for the frame about to be recorded, collecting whatever the same slot's frame
// left behind on its way past.
void begin_frame_timing(const VkCommandBuffer commandBuffer) noexcept {
    if (!ensure_timing_pool()) {
        return;
    }

    const uint32_t slot = g_timing.nextSlot;
    collect_timing(slot);
    g_timing.pending[slot] = false;
    g_timing.recordingSlot = slot;
    g_timing.nextSlot = (slot + 1) % kTimingSlotCount;
    vkCmdResetQueryPool(commandBuffer, g_timing.pool, slot * kTimingStampsPerSlot,
                        kTimingStampsPerSlot);
    vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, g_timing.pool,
                        slot * kTimingStampsPerSlot);
    g_timing.writtenStamps = 1;
}

// Closes one stage. [index] is checked against what the slot already holds, so a frame whose
// stages did not all run - a failed evaluation, a frame with no destination to copy into -
// abandons its slot instead of reporting a gap as a duration.
void mark_frame_timing(const VkCommandBuffer commandBuffer, const uint32_t index) noexcept {
    if (g_timing.pool == VK_NULL_HANDLE || g_timing.writtenStamps != index) {
        return;
    }

    vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, g_timing.pool,
                        g_timing.recordingSlot * kTimingStampsPerSlot + index);
    g_timing.writtenStamps = index + 1;
    if (g_timing.writtenStamps == kTimingStampsPerSlot) {
        g_timing.pending[g_timing.recordingSlot] = true;
    }
}

void destroy_timing() noexcept {
    if (g_timing.pool != VK_NULL_HANDLE && g_state.device != VK_NULL_HANDLE) {
        wait_device_idle();
        vkDestroyQueryPool(g_state.device, g_timing.pool, nullptr);
    }
    g_timing = DlssFrameTiming{};
}

int32_t release_feature() noexcept {
    if (g_state.feature == nullptr) {
        return kSuccess;
    }
    wait_device_idle();
    const int32_t result = static_cast<int32_t>(
        NVSDK_NGX_VULKAN_ReleaseFeature(g_state.feature));
    if (result == kSuccess) {
        g_state.feature = nullptr;
    }
    return result;
}

int32_t destroy_capability_parameters() noexcept {
    if (g_state.capabilityParameters == nullptr) {
        return kSuccess;
    }
    const int32_t result = static_cast<int32_t>(
        NVSDK_NGX_VULKAN_DestroyParameters(g_state.capabilityParameters));
    if (result == kSuccess) {
        g_state.capabilityParameters = nullptr;
    }
    return result;
}

// Destroys in the reverse of creation order: the view reads the image, the image
// owns nothing, and the memory outlives neither.
void destroy_owned_image(DlssOwnedImage& owned) noexcept {
    if (g_state.device != VK_NULL_HANDLE) {
        if (owned.view != VK_NULL_HANDLE) {
            vkDestroyImageView(g_state.device, owned.view, nullptr);
        }
        if (owned.image != VK_NULL_HANDLE) {
            vkDestroyImage(g_state.device, owned.image, nullptr);
        }
        if (owned.memory != VK_NULL_HANDLE) {
            vkFreeMemory(g_state.device, owned.memory, nullptr);
        }
    }
    owned = DlssOwnedImage{};
}

void release_images() noexcept {
    if (g_state.motionImage.image != VK_NULL_HANDLE || g_state.outputImage.image != VK_NULL_HANDLE) {
        wait_device_idle();
    }
    destroy_owned_image(g_state.motionImage);
    destroy_owned_image(g_state.outputImage);
    // A destroyed view's handle value can be handed back out by the next creation, so the
    // motion pass must forget what its descriptors describe rather than compare handles
    // against a view that no longer exists.
    g_state.motionPass.boundSet = -1;
    g_state.motionPass.boundDepthView = 0;
    g_state.motionPass.boundMotionView = 0;
    g_state.imagesRenderWidth = 0;
    g_state.imagesRenderHeight = 0;
    g_state.imagesOutputWidth = 0;
    g_state.imagesOutputHeight = 0;
}

bool find_memory_type(const uint32_t typeBits, const VkMemoryPropertyFlags properties,
                      uint32_t* index) noexcept {
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    vkGetPhysicalDeviceMemoryProperties(g_state.physicalDevice, &memoryProperties);
    for (uint32_t candidate = 0; candidate < memoryProperties.memoryTypeCount; ++candidate) {
        const bool allowed = (typeBits & (1u << candidate)) != 0;
        const VkMemoryPropertyFlags flags = memoryProperties.memoryTypes[candidate].propertyFlags;
        if (allowed && (flags & properties) == properties) {
            *index = candidate;
            return true;
        }
    }
    return false;
}

// Any failure here destroys whatever it already made, so a caller never has to
// distinguish "not created" from "half created".
int32_t create_owned_image(const uint32_t width, const uint32_t height, const VkFormat format,
                           const VkImageUsageFlags usage, DlssOwnedImage& owned) noexcept {
    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = format;
    imageInfo.extent = VkExtent3D{width, height, 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = usage;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    // Every handle is created into a local and published to `owned` only once the call
    // succeeded: Vulkan leaves the output undefined on failure, and a half-written
    // struct would hand the destroy path a garbage handle to free.
    VkImage image = VK_NULL_HANDLE;
    if (vkCreateImage(g_state.device, &imageInfo, nullptr, &image) != VK_SUCCESS) {
        return kFailure;
    }
    owned.image = image;

    VkMemoryRequirements requirements{};
    vkGetImageMemoryRequirements(g_state.device, image, &requirements);
    uint32_t memoryTypeIndex = 0;
    if (!find_memory_type(requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                          &memoryTypeIndex)) {
        destroy_owned_image(owned);
        return kFailure;
    }

    VkMemoryAllocateInfo allocateInfo{};
    allocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocateInfo.allocationSize = requirements.size;
    allocateInfo.memoryTypeIndex = memoryTypeIndex;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    if (vkAllocateMemory(g_state.device, &allocateInfo, nullptr, &memory) != VK_SUCCESS) {
        destroy_owned_image(owned);
        return kFailure;
    }
    owned.memory = memory;
    if (vkBindImageMemory(g_state.device, image, memory, 0) != VK_SUCCESS) {
        destroy_owned_image(owned);
        return kFailure;
    }

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = format;
    viewInfo.subresourceRange = VkImageSubresourceRange{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VkImageView view = VK_NULL_HANDLE;
    if (vkCreateImageView(g_state.device, &viewInfo, nullptr, &view) != VK_SUCCESS) {
        destroy_owned_image(owned);
        return kFailure;
    }
    owned.view = view;
    return kSuccess;
}

// Destroys in the reverse of creation order. Every handle is checked because this also runs
// as the cleanup path of a partially created pass.
void destroy_motion_pass() noexcept {
    DlssMotionPass& pass = g_state.motionPass;
    if (g_state.device != VK_NULL_HANDLE) {
        // A fully built pass has been dispatched from; a half-built one never was, and the
        // stall costs nothing there because nothing referencing it was ever submitted.
        if (pass.pipeline != VK_NULL_HANDLE) {
            wait_device_idle();
            vkDestroyPipeline(g_state.device, pass.pipeline, nullptr);
        }
        if (pass.pipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(g_state.device, pass.pipelineLayout, nullptr);
        }
        // The pool owns its sets; freeing them individually is neither needed nor allowed
        // without VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT.
        if (pass.descriptorPool != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(g_state.device, pass.descriptorPool, nullptr);
        }
        if (pass.setLayout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(g_state.device, pass.setLayout, nullptr);
        }
        if (pass.sampler != VK_NULL_HANDLE) {
            vkDestroySampler(g_state.device, pass.sampler, nullptr);
        }
        if (pass.shader != VK_NULL_HANDLE) {
            vkDestroyShaderModule(g_state.device, pass.shader, nullptr);
        }
    }
    pass = DlssMotionPass{};
}

// Builds the whole pass, or leaves nothing behind. Like create_owned_image, each handle is
// created into a local and published only once its call succeeded, because Vulkan leaves the
// output parameter undefined on failure and the destroy path would then free garbage.
int32_t create_motion_pass() noexcept {
    DlssMotionPass& pass = g_state.motionPass;
    if (pass.pipeline != VK_NULL_HANDLE) {
        return kSuccess;
    }

    VkShaderModuleCreateInfo shaderInfo{};
    shaderInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    shaderInfo.codeSize = sizeof(kMotionSpirV);
    shaderInfo.pCode = kMotionSpirV;
    VkShaderModule shader = VK_NULL_HANDLE;
    if (vkCreateShaderModule(g_state.device, &shaderInfo, nullptr, &shader) != VK_SUCCESS) {
        return kFailure;
    }
    pass.shader = shader;

    // The shader reads depth with texelFetch, so filtering and addressing never come into
    // play; the sampler exists only because a combined image sampler needs one.
    VkSamplerCreateInfo samplerInfo{};
    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerInfo.magFilter = VK_FILTER_NEAREST;
    samplerInfo.minFilter = VK_FILTER_NEAREST;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.borderColor = VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK;
    VkSampler sampler = VK_NULL_HANDLE;
    if (vkCreateSampler(g_state.device, &samplerInfo, nullptr, &sampler) != VK_SUCCESS) {
        destroy_motion_pass();
        return kFailure;
    }
    pass.sampler = sampler;

    VkDescriptorSetLayoutBinding bindings[2]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo setLayoutInfo{};
    setLayoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setLayoutInfo.bindingCount = 2;
    setLayoutInfo.pBindings = bindings;
    VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
    if (vkCreateDescriptorSetLayout(g_state.device, &setLayoutInfo, nullptr, &setLayout) !=
        VK_SUCCESS) {
        destroy_motion_pass();
        return kFailure;
    }
    pass.setLayout = setLayout;

    VkPushConstantRange pushConstantRange{};
    pushConstantRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushConstantRange.offset = 0;
    pushConstantRange.size = sizeof(DlssMotionPushConstants);
    VkPipelineLayoutCreateInfo pipelineLayoutInfo{};
    pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutInfo.setLayoutCount = 1;
    pipelineLayoutInfo.pSetLayouts = &setLayout;
    pipelineLayoutInfo.pushConstantRangeCount = 1;
    pipelineLayoutInfo.pPushConstantRanges = &pushConstantRange;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    if (vkCreatePipelineLayout(g_state.device, &pipelineLayoutInfo, nullptr, &pipelineLayout) !=
        VK_SUCCESS) {
        destroy_motion_pass();
        return kFailure;
    }
    pass.pipelineLayout = pipelineLayout;

    VkDescriptorPoolSize poolSizes[2]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSizes[0].descriptorCount = kMotionDescriptorRing;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[1].descriptorCount = kMotionDescriptorRing;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = kMotionDescriptorRing;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = poolSizes;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    if (vkCreateDescriptorPool(g_state.device, &poolInfo, nullptr, &descriptorPool) != VK_SUCCESS) {
        destroy_motion_pass();
        return kFailure;
    }
    pass.descriptorPool = descriptorPool;

    VkDescriptorSetLayout ringLayouts[kMotionDescriptorRing];
    for (uint32_t slot = 0; slot < kMotionDescriptorRing; ++slot) {
        ringLayouts[slot] = setLayout;
    }
    VkDescriptorSetAllocateInfo allocateInfo{};
    allocateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocateInfo.descriptorPool = descriptorPool;
    allocateInfo.descriptorSetCount = kMotionDescriptorRing;
    allocateInfo.pSetLayouts = ringLayouts;
    VkDescriptorSet sets[kMotionDescriptorRing] = {};
    if (vkAllocateDescriptorSets(g_state.device, &allocateInfo, sets) != VK_SUCCESS) {
        destroy_motion_pass();
        return kFailure;
    }
    for (uint32_t slot = 0; slot < kMotionDescriptorRing; ++slot) {
        pass.sets[slot] = sets[slot];
    }

    VkPipelineShaderStageCreateInfo stageInfo{};
    stageInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stageInfo.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    stageInfo.module = shader;
    stageInfo.pName = "main";
    VkComputePipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipelineInfo.stage = stageInfo;
    pipelineInfo.layout = pipelineLayout;
    VkPipeline pipeline = VK_NULL_HANDLE;
    if (vkCreateComputePipelines(g_state.device, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr,
                                 &pipeline) != VK_SUCCESS) {
        destroy_motion_pass();
        return kFailure;
    }
    pass.pipeline = pipeline;
    return kSuccess;
}

// Points one ring slot at this frame's depth view and the owned motion view, reusing the
// slot already describing them whenever nothing changed - which, after the first frame, is
// every frame.
VkDescriptorSet bind_motion_descriptors(const uint64_t depthView) noexcept {
    DlssMotionPass& pass = g_state.motionPass;
    const uint64_t motionView = to_uint64(g_state.motionImage.view);
    if (pass.boundSet >= 0 && pass.boundDepthView == depthView &&
        pass.boundMotionView == motionView) {
        return pass.sets[pass.boundSet];
    }

    const uint32_t slot = pass.nextSet;
    const VkDescriptorSet set = pass.sets[slot];

    VkDescriptorImageInfo depthInfo{};
    depthInfo.sampler = pass.sampler;
    depthInfo.imageView = from_uint64<VkImageView>(depthView);
    // The dispatch reads depth in the layout the transitions below leave it in.
    depthInfo.imageLayout = kDlssInputLayout;
    VkDescriptorImageInfo motionInfo{};
    motionInfo.imageView = g_state.motionImage.view;
    motionInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;

    VkWriteDescriptorSet writes[2]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = set;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[0].pImageInfo = &depthInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = set;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &motionInfo;
    vkUpdateDescriptorSets(g_state.device, 2, writes, 0, nullptr);

    pass.boundSet = static_cast<int32_t>(slot);
    pass.boundDepthView = depthView;
    pass.boundMotionView = motionView;
    pass.nextSet = (slot + 1) % kMotionDescriptorRing;
    return set;
}

int32_t shutdown_state() noexcept {
    g_state.bootstrapComplete = false;
    // Diagnostic and device-owned, so it goes before anything that could fail and leave the
    // shutdown half-done.
    destroy_timing();
    // Pipeline, descriptors, and sampler belong to the device Minecraft is about to destroy,
    // and none of them is known to NGX, so they go first and independently of it.
    destroy_motion_pass();
    // Images belong to the device NGX is about to release and Minecraft is about
    // to destroy, so they die first.
    release_images();
    // Feature must die before its parameters and NGX device state.
    int32_t result = release_feature();
    if (result != kSuccess) {
        return result;
    }
    result = destroy_capability_parameters();
    if (result != kSuccess) {
        return result;
    }
    if (g_state.initialized) {
        result = static_cast<int32_t>(NVSDK_NGX_VULKAN_Shutdown1(g_state.device));
        if (result != kSuccess) {
            return result;
        }
    }
    reset_state();
    return kSuccess;
}

int32_t cleanup_after_initialize_failure(const int32_t primaryFailure) noexcept {
    const int32_t cleanupResult = shutdown_state();
    return cleanupResult == kSuccess ? primaryFailure : cleanupResult;
}

struct DlssImageResourceInput {
    uint64_t imageView;
    uint64_t image;
    uint32_t format;
    uint32_t aspectMask;
    uint32_t baseMipLevel;
    uint32_t levelCount;
    uint32_t baseArrayLayer;
    uint32_t layerCount;
};

bool valid_image_resource(const DlssImageResourceInput& resource) noexcept {
    return resource.imageView != 0 && resource.image != 0 &&
           resource.format != static_cast<uint32_t>(VK_FORMAT_UNDEFINED) &&
           resource.aspectMask != 0 && resource.levelCount != 0 && resource.layerCount != 0;
}

NVSDK_NGX_Resource_VK make_image_view_resource(const DlssImageResourceInput& resource,
                                                const uint32_t width,
                                                const uint32_t height,
                                                const bool readWrite) noexcept {
    const VkImageSubresourceRange subresourceRange{
        static_cast<VkImageAspectFlags>(resource.aspectMask),
        resource.baseMipLevel,
        resource.levelCount,
        resource.baseArrayLayer,
        resource.layerCount,
    };
    return NVSDK_NGX_Create_ImageView_Resource_VK(
        from_uint64<VkImageView>(resource.imageView), from_uint64<VkImage>(resource.image),
        subresourceRange, static_cast<VkFormat>(resource.format), width, height, readWrite);
}

// One barrier per transition, on the caller's command buffer and nowhere else: the whole
// point of taking Minecraft's shared VkCommandBuffer is that DLSS work is ordered by the
// engine's own submission, so this records and never submits, waits, or idles the device.
//
// The masks are deliberately broad. Both stages are ALL_COMMANDS and both access masks are
// MEMORY_READ|MEMORY_WRITE because the engine's colour and depth images arrive from work
// this module cannot see - a render pass, a blit, a compute dispatch - and NGX's own
// pipeline stages are private to it. A narrower barrier would encode a guess about
// somebody else's pipeline; this one is correct for every producer at the cost of ordering
// against all of them, once per resource per frame.
void record_layout_transition(const VkCommandBuffer commandBuffer, const VkImage image,
                              const VkImageSubresourceRange& subresourceRange,
                              const VkImageLayout oldLayout,
                              const VkImageLayout newLayout) noexcept {
    if (oldLayout == newLayout) {
        return;
    }
    VkImageMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.srcAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    barrier.oldLayout = oldLayout;
    barrier.newLayout = newLayout;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange = subresourceRange;
    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                         VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 1,
                         &barrier);
}

VkImageSubresourceRange subresource_range_of(const DlssImageResourceInput& resource) noexcept {
    return VkImageSubresourceRange{
        static_cast<VkImageAspectFlags>(resource.aspectMask),
        resource.baseMipLevel,
        resource.levelCount,
        resource.baseArrayLayer,
        resource.layerCount,
    };
}

// The evaluation takes handles, not ownership, so an image is only known to be in a tracked
// layout when it is one of the two this module allocated. Anything else came from the
// engine and rests where Minecraft leaves its textures.
VkImageLayout current_layout_of(const uint64_t image) noexcept {
    if (g_state.motionImage.image != VK_NULL_HANDLE &&
        image == to_uint64(g_state.motionImage.image)) {
        return g_state.motionImage.layout;
    }
    if (g_state.outputImage.image != VK_NULL_HANDLE &&
        image == to_uint64(g_state.outputImage.image)) {
        return g_state.outputImage.layout;
    }
    return kEngineRestingLayout;
}

void note_layout_after_transition(const uint64_t image, const VkImageLayout layout) noexcept {
    if (g_state.motionImage.image != VK_NULL_HANDLE &&
        image == to_uint64(g_state.motionImage.image)) {
        g_state.motionImage.layout = layout;
    } else if (g_state.outputImage.image != VK_NULL_HANDLE &&
               image == to_uint64(g_state.outputImage.image)) {
        g_state.outputImage.layout = layout;
    }
}

int32_t query_optimal_dimensions(const uint32_t outputWidth, const uint32_t outputHeight,
                                 const uint32_t qualityMode, uint32_t* renderWidth,
                                 uint32_t* renderHeight) noexcept {
    if (!g_state.bootstrapComplete || g_state.capabilityParameters == nullptr) {
        return kNotInitialized;
    }
    if (renderWidth == nullptr || renderHeight == nullptr || outputWidth == 0 ||
        outputHeight == 0 || !valid_quality_mode(qualityMode)) {
        return kInvalidParameter;
    }

    // DLAA is anti-aliasing at native resolution: its render size is its output size by
    // definition, not by query. Asking the optimal-settings callback for it invites a driver
    // answer this module would then have to second-guess, and a zero would fail a startup that
    // has nothing wrong with it.
    if (static_cast<NVSDK_NGX_PerfQuality_Value>(qualityMode) ==
        NVSDK_NGX_PerfQuality_Value_DLAA) {
        *renderWidth = outputWidth;
        *renderHeight = outputHeight;
        return kSuccess;
    }

    void* callback = nullptr;
    NVSDK_NGX_Result result = NVSDK_NGX_Parameter_GetVoidPointer(
        g_state.capabilityParameters, NVSDK_NGX_Parameter_DLSSOptimalSettingsCallback, &callback);
    if (result != NVSDK_NGX_Result_Success) {
        return static_cast<int32_t>(result);
    }
    if (callback == nullptr) {
        return static_cast<int32_t>(NVSDK_NGX_Result_FAIL_OutOfDate);
    }

    // SDK 310.7.0 setters are void; only the callback and getters report results.
    NVSDK_NGX_Parameter_SetUI(g_state.capabilityParameters, NVSDK_NGX_Parameter_Width,
                               outputWidth);
    NVSDK_NGX_Parameter_SetUI(g_state.capabilityParameters, NVSDK_NGX_Parameter_Height,
                               outputHeight);
    NVSDK_NGX_Parameter_SetI(g_state.capabilityParameters,
                              NVSDK_NGX_Parameter_PerfQualityValue,
                              static_cast<int>(qualityMode));
    NVSDK_NGX_Parameter_SetI(g_state.capabilityParameters, NVSDK_NGX_Parameter_RTXValue, 0);

    const auto getOptimalSettings =
        reinterpret_cast<PFN_NVSDK_NGX_DLSS_GetOptimalSettingsCallback>(callback);
    result = getOptimalSettings(g_state.capabilityParameters);
    if (result != NVSDK_NGX_Result_Success) {
        return static_cast<int32_t>(result);
    }
    uint32_t queriedRenderWidth = 0;
    uint32_t queriedRenderHeight = 0;
    result = NVSDK_NGX_Parameter_GetUI(g_state.capabilityParameters,
                                        NVSDK_NGX_Parameter_OutWidth,
                                        &queriedRenderWidth);
    if (result != NVSDK_NGX_Result_Success) {
        return static_cast<int32_t>(result);
    }
    result = NVSDK_NGX_Parameter_GetUI(g_state.capabilityParameters,
                                        NVSDK_NGX_Parameter_OutHeight,
                                        &queriedRenderHeight);
    if (result != NVSDK_NGX_Result_Success) {
        return static_cast<int32_t>(result);
    }
    if (!valid_dimensions(outputWidth, outputHeight, queriedRenderWidth, queriedRenderHeight)) {
        return kInvalidParameter;
    }
    *renderWidth = queriedRenderWidth;
    *renderHeight = queriedRenderHeight;
    return kSuccess;
}

NVSDK_NGX_FeatureDiscoveryInfo make_discovery_info() noexcept {
    NVSDK_NGX_FeatureDiscoveryInfo dis;
    std::memset(&dis, 0, sizeof(dis));
    dis.SDKVersion = NVSDK_NGX_Version_API;
    dis.FeatureID = NVSDK_NGX_Feature_SuperSampling;
    dis.Identifier.IdentifierType = NVSDK_NGX_Application_Identifier_Type_Application_Id;
    dis.Identifier.v.ApplicationId = 0x0023;
    dis.ApplicationDataPath = L".";
    return dis;
}

/* Copy the i-th extension name; returns success with *extensionCount set on both
 * the count probe (name == nullptr) and a real copy. */
int32_t copy_extension_name(const uint32_t index, char* name, const uint32_t nameCapacity,
                            uint32_t* extensionCount, const uint32_t count,
                            const VkExtensionProperties* properties) noexcept {
    if (extensionCount == nullptr) {
        return kInvalidParameter;
    }
    *extensionCount = count;
    if (name == nullptr) {
        // Pure count probe: report the total count and return success.
        return kSuccess;
    }
    if (index >= count) {
        return kSuccess;
    }
    if (nameCapacity == 0 || properties == nullptr) {
        return kInvalidParameter;
    }
    const size_t length = std::strlen(properties[index].extensionName);
    if (length + 1 > nameCapacity) {
        return kInvalidParameter;
    }
    std::memcpy(name, properties[index].extensionName, length + 1);
    return kSuccess;
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

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_acquire_images(
    uint64_t* motion_image, uint64_t* motion_view, uint32_t* motion_format,
    uint64_t* output_image, uint64_t* output_view, uint32_t* output_format) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_state.bootstrapComplete) {
            return kNotInitialized;
        }
        if (motion_image == nullptr || motion_view == nullptr || motion_format == nullptr ||
            output_image == nullptr || output_view == nullptr || output_format == nullptr) {
            return kInvalidParameter;
        }
        // Dimensions come from the last configure and nowhere else, so there is no
        // second source of truth to disagree with the feature's own size.
        if (!valid_dimensions(g_state.outputWidth, g_state.outputHeight, g_state.renderWidth,
                              g_state.renderHeight)) {
            return kInvalidParameter;
        }

        const bool matchesConfiguration =
            g_state.motionImage.view != VK_NULL_HANDLE &&
            g_state.outputImage.view != VK_NULL_HANDLE &&
            g_state.imagesRenderWidth == g_state.renderWidth &&
            g_state.imagesRenderHeight == g_state.renderHeight &&
            g_state.imagesOutputWidth == g_state.outputWidth &&
            g_state.imagesOutputHeight == g_state.outputHeight;
        if (!matchesConfiguration) {
            release_images();
            // Motion is written by the engine and read by DLSS; output is written by
            // DLSS and copied into Minecraft's target.
            int32_t result = create_owned_image(
                g_state.renderWidth, g_state.renderHeight, kMotionFormat,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
                    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                    VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                g_state.motionImage);
            if (result != kSuccess) {
                release_images();
                return result;
            }
            result = create_owned_image(
                g_state.outputWidth, g_state.outputHeight, kOutputFormat,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
                    VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                g_state.outputImage);
            if (result != kSuccess) {
                release_images();
                return result;
            }
            g_state.imagesRenderWidth = g_state.renderWidth;
            g_state.imagesRenderHeight = g_state.renderHeight;
            g_state.imagesOutputWidth = g_state.outputWidth;
            g_state.imagesOutputHeight = g_state.outputHeight;
        }

        *motion_image = to_uint64(g_state.motionImage.image);
        *motion_view = to_uint64(g_state.motionImage.view);
        *motion_format = static_cast<uint32_t>(kMotionFormat);
        *output_image = to_uint64(g_state.outputImage.image);
        *output_view = to_uint64(g_state.outputImage.view);
        *output_format = static_cast<uint32_t>(kOutputFormat);
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

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_write_motion(
    const uint64_t command_buffer, const uint64_t depth_view, const uint64_t depth_image,
    const uint32_t depth_format, const uint32_t depth_aspect_mask,
    const uint32_t depth_base_mip_level, const uint32_t depth_level_count,
    const uint32_t depth_base_array_layer, const uint32_t depth_layer_count,
    const float* reprojection, const uint32_t render_width, const uint32_t render_height) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_state.bootstrapComplete) {
            return kNotInitialized;
        }
        if (command_buffer == 0 || reprojection == nullptr || render_width == 0 ||
            render_height == 0 || render_width != g_state.renderWidth ||
            render_height != g_state.renderHeight) {
            return kInvalidParameter;
        }
        const DlssImageResourceInput depthResourceInput{
            depth_view, depth_image, depth_format, depth_aspect_mask, depth_base_mip_level,
            depth_level_count, depth_base_array_layer, depth_layer_count};
        if (!valid_image_resource(depthResourceInput)) {
            return kInvalidParameter;
        }
        // The motion image is the destination, so there is nothing to write into until it
        // has been acquired for the configuration this call names.
        if (g_state.motionImage.view == VK_NULL_HANDLE ||
            g_state.imagesRenderWidth != render_width ||
            g_state.imagesRenderHeight != render_height) {
            return kNotInitialized;
        }

        const int32_t passResult = create_motion_pass();
        if (passResult != kSuccess) {
            return passResult;
        }
        const VkDescriptorSet descriptorSet = bind_motion_descriptors(depth_view);

        // Same recording discipline as the evaluation: transitions and dispatch go onto the
        // engine's command buffer, and nothing here submits, waits, or idles the device.
        const VkCommandBuffer recordingBuffer = from_uint64<VkCommandBuffer>(command_buffer);
        // The motion pass is the first thing this module records in a frame, so the frame's
        // timing opens here and everything the chain costs falls inside it.
        begin_frame_timing(recordingBuffer);
        const VkImageSubresourceRange depthRange = subresource_range_of(depthResourceInput);
        const VkImageLayout depthEntryLayout = current_layout_of(depth_image);
        record_layout_transition(recordingBuffer, from_uint64<VkImage>(depth_image), depthRange,
                                 depthEntryLayout, kDlssInputLayout);
        const VkImageSubresourceRange motionRange{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        record_layout_transition(recordingBuffer, g_state.motionImage.image, motionRange,
                                 g_state.motionImage.layout, VK_IMAGE_LAYOUT_GENERAL);
        g_state.motionImage.layout = VK_IMAGE_LAYOUT_GENERAL;

        DlssMotionPushConstants constants{};
        std::memcpy(constants.reprojection, reprojection, sizeof(constants.reprojection));
        constants.renderWidth = static_cast<int32_t>(render_width);
        constants.renderHeight = static_cast<int32_t>(render_height);

        vkCmdBindPipeline(recordingBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                          g_state.motionPass.pipeline);
        vkCmdBindDescriptorSets(recordingBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                                g_state.motionPass.pipelineLayout, 0, 1, &descriptorSet, 0,
                                nullptr);
        vkCmdPushConstants(recordingBuffer, g_state.motionPass.pipelineLayout,
                           VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(constants), &constants);
        // Rounded up to whole workgroups; the shader discards the surplus invocations.
        vkCmdDispatch(recordingBuffer,
                      (render_width + kMotionWorkgroupSize - 1) / kMotionWorkgroupSize,
                      (render_height + kMotionWorkgroupSize - 1) / kMotionWorkgroupSize, 1);

        // The dispatch's writes are not visible to anything downstream without a barrier of
        // this pass's own. The evaluation's transition of the motion image happens to provide
        // one today, but only because GENERAL and the layout DLSS reads it in differ - make
        // the layouts ever agree and record_layout_transition emits nothing, leaving the
        // evaluation reading whatever was in the image before the dispatch. The pass owns the
        // visibility of its own writes rather than inheriting it from a caller.
        VkImageMemoryBarrier motionWriteBarrier{};
        motionWriteBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        motionWriteBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        motionWriteBarrier.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
        motionWriteBarrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        motionWriteBarrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        motionWriteBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        motionWriteBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        motionWriteBarrier.image = g_state.motionImage.image;
        motionWriteBarrier.subresourceRange = motionRange;
        vkCmdPipelineBarrier(recordingBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 1,
                             &motionWriteBarrier);

        // Depth goes back where Minecraft expects it, in the same recording. The motion image
        // stays in GENERAL, which is both where the next evaluation's transition starts from
        // and where a reader of this module's own image expects to find it.
        record_layout_transition(recordingBuffer, from_uint64<VkImage>(depth_image), depthRange,
                                 kDlssInputLayout, depthEntryLayout);
        mark_frame_timing(recordingBuffer, 1);
        return kSuccess;
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_evaluate(
    const uint64_t command_buffer, const uint64_t color_view, const uint64_t color_image,
    const uint32_t color_format, const uint32_t color_aspect_mask,
    const uint32_t color_base_mip_level, const uint32_t color_level_count,
    const uint32_t color_base_array_layer, const uint32_t color_layer_count,
    const uint64_t depth_view, const uint64_t depth_image, const uint32_t depth_format,
    const uint32_t depth_aspect_mask, const uint32_t depth_base_mip_level,
    const uint32_t depth_level_count, const uint32_t depth_base_array_layer,
    const uint32_t depth_layer_count, const uint64_t motion_view, const uint64_t motion_image,
    const uint32_t motion_format, const uint32_t motion_aspect_mask,
    const uint32_t motion_base_mip_level, const uint32_t motion_level_count,
    const uint32_t motion_base_array_layer, const uint32_t motion_layer_count,
    const uint64_t output_view, const uint64_t output_image, const uint32_t output_format,
    const uint32_t output_aspect_mask, const uint32_t output_base_mip_level,
    const uint32_t output_level_count, const uint32_t output_base_array_layer,
    const uint32_t output_layer_count, const uint32_t render_width,
    const uint32_t render_height, const uint32_t output_width, const uint32_t output_height,
    const float jitter_x, const float jitter_y, const float motion_scale_x,
    const float motion_scale_y, const float frame_time_milliseconds,
    const int32_t reset_history) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_state.bootstrapComplete) {
            return kNotInitialized;
        }
        if (command_buffer == 0 || reset_history < 0 || reset_history > 1 ||
            !valid_dimensions(output_width, output_height, render_width, render_height) ||
            output_width != g_state.outputWidth || output_height != g_state.outputHeight ||
            render_width != g_state.renderWidth || render_height != g_state.renderHeight) {
            return kInvalidParameter;
        }

        const DlssImageResourceInput colorResourceInput{
            color_view, color_image, color_format, color_aspect_mask, color_base_mip_level,
            color_level_count, color_base_array_layer, color_layer_count};
        const DlssImageResourceInput depthResourceInput{
            depth_view, depth_image, depth_format, depth_aspect_mask, depth_base_mip_level,
            depth_level_count, depth_base_array_layer, depth_layer_count};
        const DlssImageResourceInput motionResourceInput{
            motion_view, motion_image, motion_format, motion_aspect_mask, motion_base_mip_level,
            motion_level_count, motion_base_array_layer, motion_layer_count};
        const DlssImageResourceInput outputResourceInput{
            output_view, output_image, output_format, output_aspect_mask, output_base_mip_level,
            output_level_count, output_base_array_layer, output_layer_count};
        if (!valid_image_resource(colorResourceInput) || !valid_image_resource(depthResourceInput) ||
            !valid_image_resource(motionResourceInput) || !valid_image_resource(outputResourceInput)) {
            return kInvalidParameter;
        }

        auto colorResource = make_image_view_resource(
            colorResourceInput, render_width, render_height, false);
        auto depthResource = make_image_view_resource(
            depthResourceInput, render_width, render_height, false);
        auto motionResource = make_image_view_resource(
            motionResourceInput, render_width, render_height, false);
        auto outputResource = make_image_view_resource(
            outputResourceInput, output_width, output_height, true);
        const bool featureMatchesConfiguration =
            g_state.feature != nullptr &&
            g_state.featureOutputWidth == output_width &&
            g_state.featureOutputHeight == output_height &&
            g_state.featureRenderWidth == render_width &&
            g_state.featureRenderHeight == render_height &&
            g_state.featureQualityMode == g_state.qualityMode &&
            g_state.featureRenderPreset == g_state.renderPreset;
        if (!featureMatchesConfiguration) {
            int32_t result = release_feature();
            if (result != kSuccess) {
                return result;
            }
            // NGX reads the preset hint when the feature is created and never again, so it is
            // written here rather than at configure time: a preset stored before the parameters
            // existed, or written after this creation, is a preset that silently never ran.
            const char* presetParameter = preset_parameter_for(g_state.qualityMode);
            if (presetParameter == nullptr) {
                return kInvalidParameter;
            }
            NVSDK_NGX_Parameter_SetUI(g_state.capabilityParameters, presetParameter,
                                      g_state.renderPreset);
            NVSDK_NGX_DLSS_Create_Params createParams{};
            createParams.Feature.InWidth = render_width;
            createParams.Feature.InHeight = render_height;
            createParams.Feature.InTargetWidth = output_width;
            createParams.Feature.InTargetHeight = output_height;
            createParams.Feature.InPerfQualityValue =
                static_cast<NVSDK_NGX_PerfQuality_Value>(g_state.qualityMode);
            createParams.InFeatureCreateFlags =
                NVSDK_NGX_DLSS_Feature_Flags_MVLowRes |
                NVSDK_NGX_DLSS_Feature_Flags_DepthInverted;
            const NVSDK_NGX_Result createResult = NGX_VULKAN_CREATE_DLSS_EXT(
                from_uint64<VkCommandBuffer>(command_buffer), 1, 1, &g_state.feature,
                g_state.capabilityParameters, &createParams);
            if (createResult != NVSDK_NGX_Result_Success) {
                g_state.feature = nullptr;
                return static_cast<int32_t>(createResult);
            }
            g_state.featureOutputWidth = output_width;
            g_state.featureOutputHeight = output_height;
            g_state.featureRenderWidth = render_width;
            g_state.featureRenderHeight = render_height;
            g_state.featureQualityMode = g_state.qualityMode;
            g_state.featureRenderPreset = g_state.renderPreset;
        }

        // Inputs into a read state and the output into a storage state, recorded on the
        // engine's own command buffer immediately before the evaluation reads them.
        const VkCommandBuffer recordingBuffer = from_uint64<VkCommandBuffer>(command_buffer);
        const VkImageLayout colorEntryLayout = current_layout_of(color_image);
        const VkImageLayout depthEntryLayout = current_layout_of(depth_image);
        record_layout_transition(recordingBuffer, from_uint64<VkImage>(color_image),
                                 subresource_range_of(colorResourceInput), colorEntryLayout,
                                 kDlssInputLayout);
        record_layout_transition(recordingBuffer, from_uint64<VkImage>(depth_image),
                                 subresource_range_of(depthResourceInput), depthEntryLayout,
                                 kDlssInputLayout);
        record_layout_transition(recordingBuffer, from_uint64<VkImage>(motion_image),
                                 subresource_range_of(motionResourceInput),
                                 current_layout_of(motion_image), kDlssInputLayout);
        note_layout_after_transition(motion_image, kDlssInputLayout);
        record_layout_transition(recordingBuffer, from_uint64<VkImage>(output_image),
                                 subresource_range_of(outputResourceInput),
                                 current_layout_of(output_image), kDlssOutputLayout);
        note_layout_after_transition(output_image, kDlssOutputLayout);

        NVSDK_NGX_VK_DLSS_Eval_Params evaluateParams{};
        evaluateParams.Feature.pInColor = &colorResource;
        evaluateParams.Feature.pInOutput = &outputResource;
        evaluateParams.pInDepth = &depthResource;
        evaluateParams.pInMotionVectors = &motionResource;
        evaluateParams.InJitterOffsetX = jitter_x;
        evaluateParams.InJitterOffsetY = jitter_y;
        evaluateParams.InRenderSubrectDimensions = {render_width, render_height};
        evaluateParams.InReset = reset_history;
        evaluateParams.InMVScaleX = motion_scale_x;
        evaluateParams.InMVScaleY = motion_scale_y;
        evaluateParams.InFrameTimeDeltaInMsec = frame_time_milliseconds;
        const int32_t evaluateResult = static_cast<int32_t>(NGX_VULKAN_EVALUATE_DLSS_EXT(
            recordingBuffer, g_state.feature, g_state.capabilityParameters, &evaluateParams));

        // The engine's images go back where Minecraft expects to find them, in the same
        // recording, whether or not the evaluation succeeded: the transitions above were
        // recorded either way, and a command buffer that is submitted with them half-undone
        // hands the renderer an image in a layout its next pass does not expect. The two
        // native images keep the layouts DLSS restores them to, which is where the next
        // frame's transitions start from.
        record_layout_transition(recordingBuffer, from_uint64<VkImage>(color_image),
                                 subresource_range_of(colorResourceInput), kDlssInputLayout,
                                 colorEntryLayout);
        record_layout_transition(recordingBuffer, from_uint64<VkImage>(depth_image),
                                 subresource_range_of(depthResourceInput), kDlssInputLayout,
                                 depthEntryLayout);
        mark_frame_timing(recordingBuffer, 2);
        return evaluateResult;
    } catch (...) {
        return kFailure;
    }
}

MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_present_output(
    const uint64_t command_buffer, const uint64_t destination_image,
    const uint32_t destination_aspect_mask, const uint32_t destination_base_mip_level,
    const uint32_t destination_level_count, const uint32_t destination_base_array_layer,
    const uint32_t destination_layer_count, const uint32_t destination_width,
    const uint32_t destination_height) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_state.bootstrapComplete) {
            return kNotInitialized;
        }
        if (command_buffer == 0 || destination_image == 0 || destination_level_count == 0 ||
            destination_layer_count == 0 ||
            destination_aspect_mask != VK_IMAGE_ASPECT_COLOR_BIT ||
            destination_width != g_state.outputWidth ||
            destination_height != g_state.outputHeight) {
            return kInvalidParameter;
        }
        // Nothing to present until the output image exists at the size this call names.
        if (g_state.outputImage.image == VK_NULL_HANDLE ||
            g_state.imagesOutputWidth != destination_width ||
            g_state.imagesOutputHeight != destination_height) {
            return kNotInitialized;
        }
        // Copying an image onto itself would be a caller that passed this module's own
        // output back as the engine target, and vkCmdCopyImage forbids the overlap.
        if (destination_image == to_uint64(g_state.outputImage.image)) {
            return kInvalidParameter;
        }

        const VkCommandBuffer recordingBuffer = from_uint64<VkCommandBuffer>(command_buffer);
        const VkImage destination = from_uint64<VkImage>(destination_image);
        const VkImageSubresourceRange destinationRange{
            static_cast<VkImageAspectFlags>(destination_aspect_mask), destination_base_mip_level,
            destination_level_count, destination_base_array_layer, destination_layer_count};
        const VkImageSubresourceRange outputRange{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        const VkImageLayout destinationEntryLayout = current_layout_of(destination_image);
        const VkImageLayout outputEntryLayout = current_layout_of(to_uint64(g_state.outputImage.image));

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
        region.dstSubresource = VkImageSubresourceLayers{
            static_cast<VkImageAspectFlags>(destination_aspect_mask), destination_base_mip_level,
            destination_base_array_layer, 1};
        region.extent = VkExtent3D{destination_width, destination_height, 1};
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
