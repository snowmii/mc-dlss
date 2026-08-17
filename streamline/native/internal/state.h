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

// The two Streamline viewport ids this module's records name. SR records - options, tags,
// the evaluation, and its constants - stay on the engine-space viewport 0; every DLSS-G
// options, state, tag, and constants record uses the FG-only viewport 1. The split is what
// lets orientation code apply y-orientation fixes to the FG side without reaching what SR
// reads.
constexpr uint32_t kSrViewportId = 0;
constexpr uint32_t kFgViewportId = 1;

// The two Reflex present markers this module emits at the present handoff, and the type tag
// the present-marker event log records each emitted marker under. The ABI exposes the raw
// values through mc_dlss_query_present_markers.
enum PresentMarkerType : uint32_t {
    kPresentMarkerStart = 0,
    kPresentMarkerEnd = 1,
    kPresentMarkerTypeCount = 2,
};

// The five Reflex/PCL frame markers this module emits at the input, simulation, and
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

// One marker event as a log records it: the marker type - a PresentMarkerType or a
// ReflexMarkerType, depending on which log holds it - and the Streamline frame index (the
// retained token) the marker was emitted under.
struct MarkerEvent {
    uint32_t type;
    uint32_t frameIndex;
};

// The number of events a marker log's ring retains, and the widest per-type count array any
// log needs (the reflex log's five types; the present log uses the first two).
constexpr uint32_t kMarkerLogSize = 16;
constexpr uint32_t kMarkerTypeCapacity = kReflexMarkerTypeCount;

// One marker log: the cumulative per-type counts of the markers this module actually emitted,
// and a ring of the most recent events in emission order.
//
// Both marker surfaces - the present bracket's two markers and the five Reflex/PCL frame
// markers - are the same log under different type vocabularies, so they are one type held
// twice rather than one ring discipline written twice. The ring math is the part that has to
// be right (the read-back walks from the oldest kept slot, which is the next one to be
// overwritten), and it exists once.
//
// A log is history, not per-frame eligibility: neither FrameEligibility::invalidate nor a
// later handoff clears one, so it keeps answering after the frame its events name has been
// dropped. Only reset_state clears it, which is what makes a fresh fork's pre-emission
// refusal observable.
struct MarkerLog {
    uint32_t typeCounts[kMarkerTypeCapacity] = {};
    uint32_t eventCount = 0;
    MarkerEvent events[kMarkerLogSize] = {};

    // Appends one marker this module actually emitted, under the frame index the retained
    // token carries. Called only after that marker's slPCLSetMarker answered eOk, so a log
    // reports exactly what reached the plugin.
    void record(uint32_t type, const sl::FrameToken* frameToken) noexcept;

    // The oracle read: the first `typeCount` per-type counts, the total event count, and the
    // most recent events in emission order as (type, frame index) pairs, at most
    // `eventsCapacity` of them. Answers kInvalidParameter on null out-pointers and
    // kNotInitialized until at least one marker was actually emitted - the refusal that makes
    // "refused sessions emit none" observable.
    int32_t read(uint32_t* outTypeCounts, uint32_t typeCount, uint32_t* outEventCount,
                 uint32_t* outEvents, uint32_t eventsCapacity) const noexcept;
};

// One frame's present eligibility: the retained Streamline frame token, the per-side SR/FG
// tag records the present handoff reads as one set, the present bracket the handoff arms, and
// the FG orientation copies recorded under that token.
//
// These nine values are one state machine, not nine flags. They were flat members of DlssState
// enforced by hand at every call site plus one free invalidate helper each caller had to
// remember, so a transition added anywhere else silently skipped invalidation and the plugin
// kept interpolating on released or stale-tagged images. Every rule the call sites used to
// state in comments is a method body here.
//
// One lock, unchanged: this is a plain member of DlssState and takes no mutex of its own. Every
// method assumes the caller already holds the single g_mutex.
class FrameEligibility {
public:
    // The Streamline frame token the frame's first tag call obtained and retained. The tags,
    // the evaluation, the constants, and the present bracket all record under this one token,
    // so every record of one frame lands on one frame index.
    sl::FrameToken* token() const noexcept { return token_; }
    bool hasToken() const noexcept { return token_ != nullptr; }
    // The slot slGetNewFrameToken writes into. The only non-const reach at the token, and the
    // reason it is a reference: the SL call takes sl::FrameToken*&.
    sl::FrameToken*& tokenSlot() noexcept { return token_; }
    // Drops the retained token alone, leaving the tag records: the SR-only frame's evaluation
    // consumes its token here, so the next tag obtains a fresh one.
    void releaseToken() noexcept { token_ = nullptr; }

    // Marks one side of the tag set fresh under the index its successful slSetTagForFrame
    // tagged under. Each call marks only its own side: a set the handoff consumed stays
    // consumed until the counterpart records too, so a partial re-tag can never revive it.
    void armSr(uint32_t frameIndex) noexcept;
    void armFg(uint32_t frameIndex) noexcept;
    bool srArmed() const noexcept { return srRecorded_; }
    bool fgArmed() const noexcept { return fgRecorded_; }
    // Whether the SR side recorded under exactly this frame index - what tells the frame's
    // post-evaluation FG tag call from its pre-evaluation one, since the SR tag sits between
    // them.
    bool srArmedAt(uint32_t frameIndex) const noexcept;
    // The composed-frame oracle read: both indexes, or false when either side never recorded.
    // Equality of two never-recorded slots is meaningless, so the refusal is the answer.
    bool tagIndexes(uint32_t* srFrameIndex, uint32_t* fgFrameIndex) const noexcept;

    // Whether both sides of the tag set are fresh under equal indexes - one frame's SR and FG
    // tags reuse one retained token, so unequal indexes are two frames' half-records rather
    // than one frame's set.
    bool tagSetComplete() const noexcept;
    // Whether the tag set can hand a composed frame off: a complete set, with the token it
    // recorded under still retained.
    bool handoffEligible() const noexcept { return tagSetComplete() && token_ != nullptr; }
    // Consumes the set the handoff accepted and arms the present bracket. Both sides' records
    // clear, so a second handoff for the same set refuses. The token deliberately survives:
    // the bracket's markers are emitted around the actual queue present, so it must stay alive
    // until consumePresent takes it.
    void consumeForHandoff() noexcept;

    bool presentArmed() const noexcept { return presentArmed_; }
    bool presentStartEmitted() const noexcept { return presentStartEmitted_; }
    // Whether a PRESENT_START marker is this present's to emit: an armed bracket, no START
    // already emitted for it, and a token to emit under. An unarmed or already-open bracket is
    // a no-op success rather than a refusal - the present seam fires on every present.
    bool presentStartPending() const noexcept;
    void markPresentStartEmitted() noexcept { presentStartEmitted_ = true; }
    // Closes the bracket and consumes the frame: the present is the composed frame's terminal
    // act, so an armed bracket whose START never emitted is consumed exactly like a successful
    // one. The tag indexes and the copy record are deliberately left standing - present_end
    // always did, and the cleared record flags already refuse every read of them.
    void consumePresent() noexcept;

    // Whether this frame still needs its three y-inverting orientation blits. The index
    // comparison is what tells the two FG tag calls of one frame apart from the first calls of
    // two frames: the retained token advances per frame, so a mismatch means a fresh frame
    // whose copies must be rebuilt.
    bool copiesNeededFor(uint32_t frameIndex) const noexcept;
    void recordCopies(uint32_t frameIndex) noexcept;

    // Drops the eligibility of any in-flight frame - the retained token, both tag records and
    // indexes, the present bracket, and the copy record. Called wherever the frame those
    // records name can no longer reach a present: configuration replacement, reset, image
    // release, and a failed slSetTagForFrame or slReflexSleep.
    void invalidate() noexcept;

private:
    sl::FrameToken* token_ = nullptr;
    bool presentArmed_ = false;
    bool presentStartEmitted_ = false;
    bool srRecorded_ = false;
    uint32_t srIndex_ = 0;
    bool fgRecorded_ = false;
    uint32_t fgIndex_ = 0;
    bool copiesRecorded_ = false;
    uint32_t copiedIndex_ = 0;
};

// The DLSS-G options record: whether the last mc_dlss_configure_fg answered success for the
// currently stored configuration, and the two values every later record re-records with. Held
// together because make_fg_options reads them together - a mode switch, a multiplier change,
// and the per-frame present handoff all re-record the same stored shape, so the options the
// plugin holds can never drift from what this carries.
struct FgOptionsRecord {
    // Whether the options recorded successfully for the currently stored configuration: the
    // last mc_dlss_configure_fg answered success and no later mc_dlss_configure or reset has
    // replaced the configuration since. The FG tag gates on this - tagging resources whose
    // extents/formats no options were recorded for would hand the plugin a frame it has no
    // configuration to interpret.
    bool recorded = false;
    // The back-buffer count the last successful mc_dlss_configure_fg declared. The handoff
    // takes no parameters: it can only re-record what a successful configure stored, and
    // re-recording a caller-supplied value each frame would let the per-frame record drift
    // from the validated configuration.
    uint32_t numBackBuffers = 0;
    // The DLSS-G frame multiplier, in numFramesToGenerate units: 1 = 2x, 2 = 3x, and so on.
    // The default 2x record stores 1, and the last successful mc_dlss_set_fg_multiplier stores
    // the cycled value. reset_state clears it back to the 2x default with the rest of the
    // struct.
    uint32_t numFramesToGenerate = 1;
};

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
    // Which flipped-motion view the current ring slot's last storage binding describes, so
    // the flipped write target participates in the same reuse comparison as the other two.
    uint64_t boundFlippedView = 0;
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
    // The DLSS-G options record every later record re-records from; make_fg_options reads it.
    FgOptionsRecord fgOptions;
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
    // The Reflex frame-rate cap the options carry, in microseconds per frame; zero is no
    // Reflex-side cap, which is what the READY registration starts at. The host re-records it
    // whenever the cap it wants changes (an FG multiplier cycle, a refresh or vsync change, a
    // vanilla framerate-option change), and the stored value is what make_reflex_options reads,
    // so the mode registration and the cap can never drift apart. Reflex's limiter is the one
    // DLSS-G tolerates: it sleeps before the frame's simulation, where the driver knows the
    // pacer's schedule, instead of spinning after Present the way an engine-side limiter does -
    // and an app frame interval that jitters is cut N ways by multi-frame generation, with every
    // sub-interval carrying the whole error.
    uint32_t reflexFrameLimitUs = 0;
    DlssOwnedImage motionImage;
    DlssOwnedImage outputImage;
    // The FG orientation copies: the DLSS-G viewport consumes the frame in the backbuffer's
    // orientation, which is the engine's mirrored about the horizontal axis (Minecraft's
    // present blit inverts y between the main target and the swapchain image), so the FG tag
    // names module-owned copies the engine images never touch: the depth at render size and
    // the HUD-less and UI buffers at output size, each filled by a y-inverting blit, and the
    // motion image flipped with its y component negated by the motion dispatches. The SR
    // viewport keeps the engine-oriented originals above; the two features genuinely hold two
    // views of one frame. Created and released with motionImage/outputImage, from the same
    // configured dimensions and for the same configuration lifetime.
    DlssOwnedImage fgDepthImage;
    DlssOwnedImage fgHudlessImage;
    DlssOwnedImage fgUiImage;
    DlssOwnedImage fgMotionImage;
    DlssMotionPass motionPass;
    uint32_t imagesRenderWidth = 0;
    uint32_t imagesRenderHeight = 0;
    uint32_t imagesOutputWidth = 0;
    uint32_t imagesOutputHeight = 0;
    // One frame's present eligibility, as one state machine rather than nine flat flags: the
    // retained Streamline frame token, the per-side SR/FG tag records the handoff reads as one
    // set, the present bracket, and the FG orientation copy record. reset_state clears it with
    // the rest of the struct.
    FrameEligibility frameEligibility;
    // The present-marker event log: one entry per PRESENT_START or PRESENT_END marker this
    // module actually emitted to Streamline, in emission order, each under the frame index
    // (the retained token) the marker was emitted with. The START is recorded the moment its
    // slPCLSetMarker call succeeds; the END only after its own call succeeds, so a handoff
    // whose END failed after its START reached the plugin reads truthfully as one START
    // event and no END event - never as a pair. Counts are indexed by PresentMarkerType.
    MarkerLog presentMarkers;
    // The reflex-marker event log: one entry per INPUT_SAMPLE, SIMULATION_START/END, or
    // RENDER_SUBMIT_START/END marker this module actually emitted to Streamline, in emission
    // order, each under the frame index (the retained token) the marker was emitted with.
    // Counts are indexed by ReflexMarkerType.
    MarkerLog reflexMarkers;
    // Whether the camera constants of a successful slSetConstants are recorded: the last
    // successful evaluation's camera, exposed by mc_dlss_query_camera_constants as the
    // constants oracle. Recorded only after slSetConstants answered eOk, so the oracle
    // means "the constants the plugin actually received", and reset_state clears it with
    // the rest of the struct, which is what makes the pre-evaluation refusal of a fresh
    // fork observable. Zero-filled by default: a caller without a camera records zeros
    // exactly as the evaluation recorded them.
    bool cameraConstantsRecorded = false;
    McDlssCameraConstants lastCameraConstants{};
    // The camera constants the last successful FG-side slSetConstants recorded, on the
    // FG-only viewport and under the same retained frame token the SR constants used:
    // exposed by mc_dlss_query_fg_camera_constants as the FG constants oracle. The record
    // carries the FG viewport's orientation: the four clip-space matrices conjugate with
    // F=diag(1,-1,1,1) and the jitter's y negates, matching the y-flipped images the FG
    // tag names, while the SR record above stays raw - the two oracles report exactly what
    // each viewport received. Recorded only after the FG-viewport slSetConstants answered
    // eOk and only for composed frames (a frame whose FG tag recorded), so the oracle means
    // "the constants the FG viewport actually received" and an SR-only frame never
    // establishes the record - the independence the viewport split exists to prove.
    // reset_state clears it with the rest of the struct, so a fresh fork answers the same
    // refusal as a pre-evaluation one.
    bool fgCameraConstantsRecorded = false;
    McDlssCameraConstants lastFgCameraConstants{};
};

extern DlssState g_state;
extern std::mutex g_mutex;

void reset_state() noexcept;

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
