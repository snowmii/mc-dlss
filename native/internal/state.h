#ifndef MC_DLSS_INTERNAL_STATE_H
#define MC_DLSS_INTERNAL_STATE_H

#include "internal/common.h"

#include "mc_dlss.h"
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

// The two Reflex present markers this module emits at the present handoff, and the type tag
// the present-marker event log records each emitted marker under. The ABI exposes the raw
// values through mc_dlss_query_present_markers.
enum PresentMarkerType : uint32_t {
    kPresentMarkerStart = 0,
    kPresentMarkerEnd = 1,
};

// One present-marker event as the log records it: the marker type and the Streamline frame
// index (the retained token) the marker was emitted under.
struct PresentMarkerEvent {
    PresentMarkerType type;
    uint32_t frameIndex;
};

// The number of events the present-marker log ring retains. The ring keeps the most recent
// events for the ordered read-back; the per-type counts stay cumulative beyond it.
constexpr uint32_t kPresentMarkerLogSize = 16;

// The five Reflex/PCL frame markers this module emits at the M-12 input, simulation, and
// render-submit seams, and the type tag the reflex-marker event log records each emitted
// marker under. The ABI exposes the raw values through mc_dlss_query_reflex_markers.
// kReflexMarkerTypeCount is the array width the per-type counts use; it must stay the
// number of enum values.
enum ReflexMarkerType : uint32_t {
    kReflexMarkerInputSample = 0,
    kReflexMarkerSimulationStart = 1,
    kReflexMarkerSimulationEnd = 2,
    kReflexMarkerRenderSubmitStart = 3,
    kReflexMarkerRenderSubmitEnd = 4,
    kReflexMarkerTypeCount = 5,
};

// One reflex-marker event as the log records it: the marker type and the Streamline frame
// index (the retained token) the marker was emitted under.
struct ReflexMarkerEvent {
    ReflexMarkerType type;
    uint32_t frameIndex;
};

// The number of events the reflex-marker log ring retains, the same ring discipline as the
// present-marker log: the ring keeps the most recent events for the ordered read-back; the
// per-type counts stay cumulative beyond it.
constexpr uint32_t kReflexMarkerLogSize = 16;

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
    // The DLSS-G frame multiplier the recorded options carry, in numFramesToGenerate units:
    // 1 = 2x, 2 = 3x, and so on. The default 2x record stores 1, and the last successful
    // mc_dlss_set_fg_multiplier stores the cycled value; every FG options record - configure,
    // mode switch, present handoff - re-records it through make_fg_options, so the options the
    // plugin holds and this stored value can never drift apart. reset_state clears it back to
    // the 2x default with the rest of the struct.
    uint32_t fgNumFramesToGenerate = 1;
    // The Reflex options registration the READY transition records: one slReflexSetOptions
    // call carrying sl::ReflexMode::eLowLatency, which the pinned Reflex guide requires
    // even when Reflex Low Latency is off and there is no Reflex UI ("call at least once";
    // the call need not repeat per frame while the options do not change).
    // reflexOptionsRecorded is whether that call answered eOk, reflexMode is the mode value
    // it recorded (1 = eLowLatency), and reflexSetOptionsCalls counts every slReflexSetOptions
    // call this session made, so the exactly-once-at-READY discipline is provable: the
    // idempotent re-initialize and the per-frame path must not add calls. reset_state clears
    // all three with the rest of the struct.
    bool reflexOptionsRecorded = false;
    uint32_t reflexMode = 0;
    uint32_t reflexSetOptionsCalls = 0;
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
    // Present-start arms this retained token; present-end consumes it after queue present.
    bool presentTokenArmed = false;
    // Whether the armed bracket's PRESENT_START marker actually reached the plugin.
    // present_end emits the closing marker only after a successful START, and consumes an
    // armed bracket whose START never emitted exactly like a successful one - the frame
    // presented either way, so nothing may be left for a later present to open.
    bool presentStartEmitted = false;
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
    // consumed handoff on its own. Configuration replacement, reset, image release, and a
    // failed slSetTagForFrame all clear the four (with the retained token) through
    // invalidate_frame_eligibility, so records from a replaced configuration, a released
    // image lifecycle, or a failed tag call can never satisfy a handoff.
    bool srTagFrameIndexRecorded = false;
    uint32_t lastSrTagFrameIndex = 0;
    bool fgTagFrameIndexRecorded = false;
    uint32_t lastFgTagFrameIndex = 0;
    // The present-marker event log: one entry per PRESENT_START or PRESENT_END marker this
    // module actually emitted to Streamline, in emission order, each under the frame index
    // (the retained token) the marker was emitted with. The START is recorded the moment its
    // slPCLSetMarker call succeeds; the END only after its own call succeeds, so a handoff
    // whose END failed after its START reached the plugin reads truthfully as one START
    // event and no END event - never as a pair. The per-type counts are cumulative across
    // the session; the ring keeps only the most recent kPresentMarkerLogSize events for the
    // ordered read-back. The log is history, not per-frame eligibility: it is neither
    // cleared by invalidate_frame_eligibility nor consumed by a later handoff, so it keeps
    // answering after the frame's eligibility is dropped. reset_state clears it with the
    // rest of the struct, which is what makes the pre-ready refusal of a fresh fork
    // observable.
    uint32_t presentMarkerStartCount = 0;
    uint32_t presentMarkerEndCount = 0;
    uint32_t presentMarkerEventCount = 0;
    PresentMarkerEvent presentMarkerLog[kPresentMarkerLogSize] = {};
    // The reflex-marker event log: one entry per INPUT_SAMPLE, SIMULATION_START/END, or
    // RENDER_SUBMIT_START/END marker this module actually emitted to Streamline, in emission
    // order, each under the frame index (the retained token) the marker was emitted with.
    // The per-type counts are cumulative across the session; the ring keeps only the most
    // recent kReflexMarkerLogSize events for the ordered read-back. Same history-not-
    // eligibility semantics as the present-marker log: neither invalidate_frame_eligibility
    // nor a later handoff clears it, and only reset_state does, which is what makes the
    // pre-ready refusal of a fresh fork observable.
    uint32_t reflexMarkerTypeCounts[kReflexMarkerTypeCount] = {};
    uint32_t reflexMarkerEventCount = 0;
    ReflexMarkerEvent reflexMarkerLog[kReflexMarkerLogSize] = {};
    // Whether the camera constants of a successful slSetConstants are recorded: the last
    // successful evaluation's camera, exposed by mc_dlss_query_camera_constants as the
    // constants oracle. Recorded only after slSetConstants answered eOk, so the oracle
    // means "the constants the plugin actually received", and reset_state clears it with
    // the rest of the struct, which is what makes the pre-evaluation refusal of a fresh
    // fork observable. Zero-filled by default: a caller without a camera records zeros
    // exactly as the evaluation recorded them.
    bool cameraConstantsRecorded = false;
    McDlssCameraConstants lastCameraConstants{};
};

extern DlssState g_state;
extern std::mutex g_mutex;

void reset_state() noexcept;

// Drops the present-handoff eligibility of any in-flight frame: the retained Streamline
// frame token and the SR/FG tag records and indexes. Called wherever the frame those records
// name can no longer reach a present - configuration replacement, reset, image release, and
// a failed slSetTagForFrame - so a stale record can never satisfy a later handoff once the
// configuration it was recorded for was replaced, the frame's resources are gone, or the
// frame's tag call failed.
void invalidate_frame_eligibility() noexcept;

// Records the DLSS-G frame multiplier (implemented in sl_dlss.cpp, where the sl:: vocabulary
// lives): re-records the stored options in the eOn mode with numFramesToGenerate set to
// `numFramesToGenerate`, validated against the device's numFramesToGenerateMax read fresh
// from slDLSSGGetState. Same ready/options gates as the mode record; a value outside
// 1..max answers kInvalidParameter and changes nothing.
int32_t record_fg_multiplier(uint32_t numFramesToGenerate) noexcept;

// The multiplier oracle (implemented in sl_dlss.cpp): the numFramesToGenerate the recorded
// options carry, and the device's numFramesToGenerateMax read fresh from slDLSSGGetState.
// Same gates as the DLSS-G state read; both out-pointers are required.
int32_t query_fg_multiplier(uint32_t* current, uint32_t* max) noexcept;

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
