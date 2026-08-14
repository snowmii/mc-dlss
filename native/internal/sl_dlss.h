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

// Drops the present-handoff eligibility of any in-flight frame: the retained Streamline
// frame token and the SR/FG tag records and indexes. Called wherever the frame those records
// name can no longer reach a present - configuration replacement, reset, and image release -
// so a stale record can never satisfy a later handoff once the configuration it was recorded
// for was replaced or the frame's resources are gone.
void invalidate_frame_eligibility() noexcept;

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
// DLSSGState::status, whose fallback is the status-owning slice's to drive.
int32_t wait_fg_inputs_idle() noexcept;

// The wait oracle: performs the same value-aware timeline-semaphore wait the session-driven
// entry above performs, on explicit Vulkan device and semaphore handles and an explicit
// value. Exposed across the ABI so the value-aware wait is provable without a live
// Streamline session - the headless proof creates its own timeline semaphore, waits for a
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
// present-generation rung polls to observe the interposed present path processing frames.
int32_t query_fg_state(uint32_t* status, uint32_t* numFramesPresented,
                       uint64_t* lastPresentInputsProcessingFenceValue,
                       uint64_t* inputsProcessingCompletionFence) noexcept;

} // namespace mc_dlss

#endif
