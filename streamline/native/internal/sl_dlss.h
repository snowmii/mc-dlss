#ifndef MC_DLSS_INTERNAL_SL_DLSS_H
#define MC_DLSS_INTERNAL_SL_DLSS_H

#include "internal/state.h"

#include <cstdint>

/*
 * Everything that talks to the Streamline DLSS feature: the optimal-dimension query, the
 * per-viewport DLSS options, and the per-frame resource tags. The rest of the module deals in
 * Vulkan objects and NGX-valued ABI parameters; the sl:: types and result codes live here.
 */
namespace mc_dlss {

// Whether the Streamline session can answer DLSS calls: bootstrap has run and a Vulkan device
// has been recorded through mc_dlss_activate_vulkan_proxies (slSetVulkanInfo done).
bool sl_session_ready() noexcept;

// Shuts the Streamline runtime down while the caller's Vulkan device is still alive, after the
// module's own resources have been released. Streamline's plugins keep their worker threads
// (CUDA/NGX) running until slShutdown, and those threads reach into the live device: leaving
// them running through device/process teardown is what crashes process exit in sl.common.dll
// or nvcuda64.dll. Called by the teardown ordering unit; reset_state then clears the bootstrap
// flag with the rest of the struct, so a later mc_dlss_bootstrap_streamline re-runs slInit.
void shutdown_streamline() noexcept;

// Answers from slDLSSGetOptimalSettings. Validates like the NGX query it replaces: non-zero
// output dimensions, a valid NGX-valued quality mode, and sane dimensions coming back. DLAA
// is anti-aliasing at native resolution, so it returns the output dimensions without asking.
int32_t query_optimal_dimensions_sl(uint32_t outputWidth, uint32_t outputHeight,
                                    uint32_t qualityMode, uint32_t* renderWidth,
                                    uint32_t* renderHeight) noexcept;

// Records the configuration currently stored with slDLSSSetOptions, mapping the NGX-valued
// quality mode onto sl::DLSSMode and the render preset onto the preset field sl::DLSSOptions
// carries for that mode. Requires sl_session_ready; returns kNotInitialized otherwise.
int32_t record_sr_options() noexcept;

// Records the DLSS-G per-frame 2x options with slDLSSGSetOptions: mode eOn, one generated
// frame per real one, retained resources while off, UI recomposition, the queue-parallelism
// mode, the declared back-buffer count, the render/output extents from the stored
// configuration, and the five required formats. Requires sl_session_ready; returns
// kNotInitialized without it and kInvalidParameter while the stored dimensions are still
// zero (no successful mc_dlss_configure yet).
int32_t record_fg_options(uint32_t numBackBuffers) noexcept;

// Switches the recorded DLSS-G options' mode through slDLSSGSetOptions: eOn when
// `fgEnabled` is non-zero, eOff when it is zero, both retaining resources and reusing the
// back-buffer count and extents the last successful record stored. Requires sl_session_ready
// (kNotInitialized) and a stored DLSS-G options record with valid dimensions
// (kInvalidParameter) - the mode record switches an existing record, it never creates one,
// so a session whose options never recorded cannot be switched off. This is the native half
// of the status-latch fallback: after a non-OK slDLSSGGetState status the session re-records
// the options in the eOff mode so the plugin stops interpolating while its allocations stay
// alive; the re-arm refusal that keeps eOn from coming back is the Kotlin policy's, not
// this record's.
int32_t record_fg_mode(uint32_t fgEnabled) noexcept;

// Records the frame's present-handoff eligibility: re-records the stored DLSS-G 2x options
// through slDLSSGSetOptions with the back-buffer count the last successful
// mc_dlss_configure_fg declared, accepting exactly one complete current-frame SR+FG tag set
// under equal frame indexes. Requires sl_session_ready, recorded FG options, the module's
// images at the configured size, and both tag records fresh under one frame index; missing
// options, partial tags, and consumed/stale eligibility return kInvalidParameter before
// anything is re-recorded, so a refused handoff leaves the tag state and the options exactly
// as they were. A successful handoff consumes the frame's tag set by clearing both sides'
// records (so the set is eligible again only when both sides re-record under equal indexes)
// and arms the present bracket; unlike the pre-bracket design, the retained token survives
// the handoff, because the bracket's markers are emitted around the actual queue present -
// present_start emits PRESENT_START, present_end emits PRESENT_END and consumes the token.
// A bracket whose PRESENT_END failed also consumes the frame exactly like a successful one:
// its START already reached the plugin, so a retry would emit a second START for the same
// frame, and the error returns with the frame ineligible instead. Records no GPU work: the
// frame's tagged resources stay in the layouts the tags declared (GENERAL depth and motion)
// until Streamline's present path consumes them.
int32_t record_present_handoff() noexcept;
int32_t present_start() noexcept;
int32_t present_end() noexcept;

// Emits the frame's Reflex/PCL markers at the input, simulation, and render-submit
// seams, all under the retained Streamline frame token. The input-sample seam obtains the
// token for the frame (slGetNewFrameToken is called again even when a token is retained,
// because a retained token at frame start belongs to a previous frame that never reached
// its present end - the same stale-token edge the tag calls already tolerate) and runs the
// unconditional slReflexSleep against it, so the sleep that used to live at the tag's
// token-obtain point stays once per frame at frame start; the tag calls keep their own
// obtain-and-sleep for the callers that never run an input sample (tests, direct tag
// sequences), and because they only sleep when they themselves obtain the token, a frame
// that ran its input sample sleeps exactly once. The input seam emits ePCLatencyPing only
// after the installed window hook receives PclState::statsWindowMessage, not once per frame.
// The simulation and render-submit markers travel as a value rather than as an entry point
// each: reflex_marker takes the ReflexMarkerType and is the one place that vocabulary becomes
// an sl::PCLMarker. It requires the ready session (kNotInitialized), refuses when no token is
// retained (kNotInitialized) - a frame that never ran its input sample emits no markers -
// refuses a type outside the four it emits (kInvalidParameter, which is what INPUT_SAMPLE
// answers: that seam is its own entry because it obtains the token and sleeps first), and
// records the emitted marker in the reflex-marker log only after its slPCLSetMarker call
// succeeded, so the oracle reports exactly what reached the plugin.
// Installs the Win32 message hook that records PCL's periodic stats message. The next
// input-sample seam emits one ePCLatencyPing under that frame's token.
int32_t install_pcl_window(uint64_t hwnd) noexcept;
int32_t reflex_input_sample() noexcept;
int32_t reflex_marker(uint32_t markerType) noexcept;

// The reflex-marker oracle: how many of each of the five Reflex/PCL markers this module has
// actually emitted (per-type cumulative counts, in ReflexMarkerType order), the total event
// count, and the recent event log in emission order. Each log entry is a (type, frame index)
// pair: the type is a ReflexMarkerType value and the frame index is the Streamline frame
// token the marker was emitted under. The index must equal the frame index the frame's SR/FG
// tags and its common constants recorded under - the input sample obtains the token the rest
// of the frame reuses - which is what proves the marker surface shares the retained token
// identity. Answers kInvalidParameter on null out-pointers and kNotInitialized until at
// least one marker was actually emitted, the same refusal a fresh fork's module answers,
// which is what makes "refused sessions emit none" observable.
int32_t query_reflex_markers(uint32_t* typeCounts, uint32_t* eventCount, uint32_t* events,
                             uint32_t eventsCapacity) noexcept;

// Records the Reflex options registration the READY transition makes: one slReflexSetOptions
// call carrying sl::ReflexMode::eLowLatency and the SDK-default frame-limit and marker
// fields - the single call the pinned Reflex guide requires even when Reflex Low Latency
// would be off and there is no Reflex UI, and which the guide says not to repeat per frame
// while the options do not change. Requires sl_session_ready (kNotInitialized). On success
// the mode and the recorded flag are retained in state so the oracle answers; a failed call
// records nothing but still counts as the one call this transition made, so the oracle
// reports the attempt truthfully either way. The ABI layer (mc_dlss_initialize) decides how
// a failure surfaces; this record never latches anything.
int32_t record_reflex_options() noexcept;

// Re-records the Reflex options with a frame-rate cap: frameLimitUs microseconds per frame,
// zero for no Reflex-side cap. Reflex's limiter is the one DLSS-G tolerates - it sleeps
// before the frame's simulation, where the driver is aware of the pacer's schedule, instead
// of spinning after Present the way an engine-side limiter does. Requires sl_session_ready
// (kNotInitialized) and an existing registration (kInvalidParameter): the cap joins the
// READY record rather than creating one. A cap that is already in effect records nothing and
// answers kSuccess, so the per-frame seam that reads Minecraft's limit can call it every
// frame; a refused record restores the stored cap. Never latches anything.
int32_t record_reflex_frame_limit(uint32_t frameLimitUs) noexcept;

// The reflex-options oracle: the sl::ReflexMode value the recorded options carry (1 =
// eLowLatency) and how many slReflexSetOptions calls this session made, so the test can
// prove the registration succeeded at READY and that neither the idempotent re-initialize
// nor the per-frame path called the plugin again. Answers kNotInitialized while the
// Streamline session is not ready and kInvalidParameter while no record exists or either
// out-pointer is null.
int32_t query_reflex_options(uint32_t* mode, uint32_t* setOptionsCalls) noexcept;

// The present-marker oracle: reports how many PRESENT_START and PRESENT_END markers this
// module has actually emitted (per-type cumulative counts), the total event count, and the
// recent event log in emission order. Each log entry is a (type, frame index) pair: the
// type is a PresentMarkerType value and the frame index is the Streamline frame token the
// marker was emitted under. The index must equal the frame indexes the SR/FG tags and the
// common constants recorded under - all four record against the same retained frame token -
// and the counts must each advance by exactly one per successful handoff, which is what
// proves the "exactly one PRESENT_START then PRESENT_END per handoff" half of the
// present-marker invariant: the START and END events are recorded separately and in order,
// so a handoff whose END failed reads as one START event and no END rather than as a pair.
// Answers kInvalidParameter on null out-pointers and kNotInitialized until at least one
// marker was actually emitted (the same refusal a fresh fork's module answers, which is what
// makes "refused or pre-ready handoffs emit no markers" observable).
int32_t query_present_markers(uint32_t* startCount, uint32_t* endCount, uint32_t* eventCount,
                              uint32_t* events, uint32_t eventsCapacity) noexcept;

// Tags one frame's DLSS SR resources on the caller's command buffer via slGetNewFrameToken +
// slSetTagForFrame. The engine's colour and depth are always tagged; the module's motion and
// output images are tagged as well once they have been acquired for the configured size. The
// frame token this call obtains is retained in state for the evaluation to consume, so a
// repeated tag for the same frame reuses the token rather than advancing the frame.
int32_t tag_sr_resources(const McDlssTagInfo& info) noexcept;

// Tags one frame's DLSS-G resources on the caller's command buffer via slGetNewFrameToken +
// slSetTagForFrame: the engine's render-sized depth (D32_SFLOAT), its output-sized HUD-less
// colour and UI colour+alpha (both R8G8B8A8_UNORM), and the module's own motion image as the
// motion-vector source. The formats must match the ones the FG options recorded. All four tag
// with eValidUntilPresent against the same frame token the SR tag obtains and retains for the
// frame, so a repeated tag reuses the token rather than advancing the frame and the frame's
// evaluation consumes it. Requires the FG options to have recorded successfully for the
// stored configuration and the module's images to exist at the configured size; returns
// kInvalidParameter before either.
int32_t tag_fg_resources(const McDlssFgTagInfo& info) noexcept;

// Records the frame's DLSS SR evaluation on the caller's command buffer: the per-frame
// constants (slSetConstants) and then the feature evaluation (slEvaluateFeature), both on the
// frame token mc_dlss_tag_sr_resources obtained and retained for this frame. Consuming the
// token clears it. The caller owns the layout transitions around this call and restores them
// whether or not it succeeds.
int32_t record_sr_evaluation(const McDlssEvaluateInfo& info,
                             VkCommandBuffer commandBuffer) noexcept;

// The camera-constants oracle: copies the camera constants the last successful slSetConstants
// recorded into out. Answers kInvalidParameter on a null out pointer and kNotInitialized
// until an evaluation recorded constants at least once.
int32_t query_camera_constants(McDlssCameraConstants* out) noexcept;

// The FG camera-constants oracle: copies the camera constants the last successful FG-side
// slSetConstants recorded on the FG-only viewport into out. Answers kInvalidParameter on a
// null out pointer and kNotInitialized until a composed frame's FG-viewport record
// established the record at least once - an SR-only evaluation never does, which is the
// FG side's independence from the SR constants record.
int32_t query_fg_camera_constants(McDlssCameraConstants* out) noexcept;

// Blocks until Streamline's DLSS-G input processing for the previously presented frame has
// completed, on the caller's (present/render) thread and through the Vulkan device.
//
// The DLSS-G options record eBlockNoClientQueues, under which the plugin reads the tagged
// inputs of a presented frame on its own queues after Present; the guide requires the host to
// wait on DLSSGState::inputsProcessingCompletionFence - a Vulkan timeline semaphore on this
// API, read together with DLSSGState::lastPresentInputsProcessingCompletionFenceValue via
// slDLSSGGetState - before it modifies or destroys those inputs in a later frame. A null
// semaphore - the plugin has not allocated one, as before the first present - is a no-op
// success. Requires the ready session (kNotInitialized) and the recorded DLSS-G options
// (kInvalidParameter) like the FG tag; the wait deliberately does not look at
// DLSSGState::status, whose fallback belongs to the status policy.
int32_t wait_fg_inputs_idle() noexcept;

// The wait oracle: performs the same value-aware timeline-semaphore wait the session-driven
// entry above performs, on explicit Vulkan device and semaphore handles and an explicit
// value. Exposed across the ABI so tests can check value semantics without a live
// Streamline session - the headless test creates its own timeline semaphore, waits for a
// value the semaphore has not reached, and observes the wait block until the value is
// signaled. Requires non-null device and semaphore handles (kInvalidParameter) and touches
// no module or Streamline state.
int32_t wait_fg_inputs_value(uint64_t vkDevice, uint64_t semaphore, uint64_t value) noexcept;

// Reads the live DLSS-G state through slDLSSGGetState: the status word, actual presentations
// per app frame (two means one real plus one generated), the value the input-processing
// completion timeline semaphore last reached for the presented frames' inputs, and the
// semaphore handle itself. Requires the ready session (kNotInitialized) and the recorded
// DLSS-G options (kInvalidParameter), the same gates as the FG tag and the input wait; a
// session whose options never recorded has no DLSS-G state to read. All four outputs are
// required - a null output is a caller that did not fill the call (kInvalidParameter).
// The call itself performs no GPU work and never blocks: the fence value is what the
// present-generation test polls to observe the interposed present path processing frames.
int32_t query_fg_state(uint32_t* status, uint32_t* numFramesPresented,
                       uint64_t* lastPresentInputsProcessingFenceValue,
                       uint64_t* inputsProcessingCompletionFence) noexcept;

} // namespace mc_dlss

#endif
