#ifndef MC_DLSS_INTERNAL_STATE_H
#define MC_DLSS_INTERNAL_STATE_H

#include "internal/common.h"

#include <sl_core_types.h>

#include <mutex>

/*
 * The module's one piece of mutable state, and the operations that read or write it without
 * needing anything above it.
 *
 * Everything that owns a GPU object - timing, images, motion - reads `g_state` and is layered
 * above this unit. Teardown, which has to drive all of them in order, lives above them all in
 * session.h rather than here: putting it here would make the state unit depend on its own
 * dependents. The Streamline runtime itself is process-wide and never enters this struct; the
 * bootstrap flag, the proxy tuple, and the frame token are the only per-module Streamline
 * state it carries, and close clears all of it so a later bootstrap can reinitialize.
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
    // Whether mc_dlss_initialize validated and recorded the live Vulkan tuple. This is what
    // the module-owned images and motion pass gate on; Streamline readiness is tracked
    // separately by streamlineInitialized + the proxy tuple, and the extension/feature/option
    // seams gate on those.
    bool sessionReady = false;
    // Whether this module instance's Streamline bootstrap succeeded: slInit answered eOk (or
    // one of the two errors that mean the runtime is already up in this process). Activation,
    // which records the Vulkan device, is tracked separately by the proxy tuple below. Close
    // resets the flag with the rest of the struct after slShutdown, so a later bootstrap runs
    // slInit again instead of treating the shutdown runtime as already up.
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
    uint32_t outputWidth = 0;
    uint32_t outputHeight = 0;
    uint32_t renderWidth = 0;
    uint32_t renderHeight = 0;
    uint32_t qualityMode = 0;
    uint32_t renderPreset = 0;
    // Whether the DLSS-G options recorded successfully for the currently stored
    // configuration: the last mc_dlss_configure_fg answered success and no later
    // mc_dlss_configure or reset has replaced the configuration since. The FG tag gates on
    // this - tagging resources whose extents/formats no options were recorded for would hand
    // the plugin a frame it has no configuration to interpret.
    bool fgOptionsRecorded = false;
    // The back-buffer count the last successful mc_dlss_configure_fg declared, stored so the
    // per-frame present handoff re-records the DLSS-G options with the same count the
    // configuration was validated against. The handoff takes no parameters: it can only
    // re-record what a successful configure stored, and re-recording a caller-supplied value
    // each frame would let the per-frame record drift from the validated configuration.
    uint32_t fgNumBackBuffers = 0;
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
    // The Streamline frame indices the last SR and FG tag calls recorded under, exposed by
    // mc_dlss_query_tagged_frame_indexes as the composed-rung oracle: one frame's SR and FG
    // tags must land under the same index (the FG tag reuses the SR tag's retained token rather
    // than advancing the frame), and the test asserts the two records are equal. The booleans
    // separate "never recorded" from a genuine index of 0; a failed tag records nothing, and
    // reset_state clears all four with the rest of the struct.
    //
    // The booleans are also the per-side present-handoff freshness of the current tag set:
    // each tag call marks only its own side fresh, the handoff accepts only a set whose two
    // sides are both fresh under equal indexes, and a successful handoff consumes the set by
    // clearing both flags. Consumed eligibility is therefore exactly "one of the two flags is
    // clear": repeating only one tag side after a handoff re-arms only that side, and the set
    // stays refused until the counterpart records too - a partial re-tag can never revive a
    // consumed handoff on its own. Configuration replacement, reset, and image release clear
    // all four (with the retained token) through invalidate_frame_eligibility, so records
    // from a replaced configuration or a released image lifecycle can never satisfy a handoff.
    bool srTagFrameIndexRecorded = false;
    uint32_t lastSrTagFrameIndex = 0;
    bool fgTagFrameIndexRecorded = false;
    uint32_t lastFgTagFrameIndex = 0;
};

extern DlssState g_state;
extern std::mutex g_mutex;

void reset_state() noexcept;

// Drops the present-handoff eligibility of any in-flight frame: the retained Streamline
// frame token and the SR/FG tag records and indexes. Called wherever the frame those records
// name can no longer reach a present - configuration replacement, reset, and image release -
// so a stale record can never satisfy a later handoff once the configuration it was recorded
// for was replaced or the frame's resources are gone.
void invalidate_frame_eligibility() noexcept;

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
