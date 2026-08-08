#ifndef MC_DLSS_INTERNAL_STATE_H
#define MC_DLSS_INTERNAL_STATE_H

#include "internal/common.h"

#include <sl_core_types.h>

#include <mutex>
#include <string>

/*
 * The module's one piece of mutable state, and the operations that read or write it without
 * needing anything above it.
 *
 * Everything that owns a GPU object - timing, images, motion, the NGX feature - reads
 * `g_state` and is layered above this unit. Teardown, which has to drive all of them in
 * order, lives above them all in session.h rather than here: putting it here would make the
 * state unit depend on its own dependents.
 */
namespace mc_dlss {

// The number of descriptor sets the motion pass rotates through. The pass writes its
// descriptor set only when the depth or motion view actually changes, which happens when
// Minecraft recreates its scene target and never per frame. A small ring means such a
// rewrite lands on a set no in-flight submission is still reading, without the pass having
// to track frame completion it has no way to observe.
constexpr uint32_t kMotionDescriptorRing = 4;

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

struct DlssState {
    bool initialized = false;
    bool bootstrapComplete = false;
    // Whether this module instance's Streamline bootstrap succeeded: slInit answered eOk (or
    // one of the two errors that mean the runtime is already up in this process). Activation,
    // which records the Vulkan device, is tracked separately by the proxy tuple below.
    bool streamlineInitialized = false;
    uint64_t instanceValue = 0;
    uint64_t physicalDeviceValue = 0;
    uint64_t deviceValue = 0;
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    // The layout mc_dlss_activate_vulkan_proxies last handed to slSetVulkanInfo. Zero means no
    // device has been recorded yet; a non-zero device with an identical tuple is the idempotent
    // repeat that must not re-call slSetVulkanInfo.
    uint64_t proxyInstance = 0;
    uint64_t proxyPhysicalDevice = 0;
    uint64_t proxyDevice = 0;
    uint32_t proxyGraphicsQueueFamily = 0;
    uint32_t proxyGraphicsQueueIndex = 0;
    uint32_t proxyComputeQueueFamily = 0;
    uint32_t proxyComputeQueueIndex = 0;
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
    // The Streamline frame token the last mc_dlss_tag_sr_resources call obtained and retained
    // for the current frame. The evaluation consumes it (slSetConstants + slEvaluateFeature run
    // against it and it is cleared), so a tag always precedes an evaluate and the next frame's
    // tag obtains the next token. reset_state clears it with the rest of the struct.
    sl::FrameToken* frameToken = nullptr;
};

extern DlssState g_state;
extern std::mutex g_mutex;

void reset_state() noexcept;

// The module's own images have to exist, at the size the configuration stores, before anything
// can be recorded into or out of them - and before they can be tagged for a frame.
bool images_match_configuration() noexcept;

// Destroying a resource the GPU may still be reading is the one Vulkan error nothing
// reports where it happens: the queued command buffers that reference it keep running,
// the driver loses the device some frames later, and the crash surfaces in whatever
// unrelated call waits on a semaphore next. Every destroy path here stalls first, and
// the stall is affordable because none of them runs per frame - they run when the
// configuration changes or the session ends.
void wait_device_idle() noexcept;

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
void record_layout_transition(VkCommandBuffer commandBuffer, VkImage image,
                              const VkImageSubresourceRange& subresourceRange,
                              VkImageLayout oldLayout, VkImageLayout newLayout) noexcept;

// The evaluation takes handles, not ownership, so an image is only known to be in a tracked
// layout when it is one of the two this module allocated. Anything else came from the
// engine and rests where Minecraft leaves its textures.
VkImageLayout current_layout_of(uint64_t image) noexcept;

void note_layout_after_transition(uint64_t image, VkImageLayout layout) noexcept;

} // namespace mc_dlss

#endif
