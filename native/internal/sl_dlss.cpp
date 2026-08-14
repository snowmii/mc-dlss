#include "internal/sl_dlss.h"

#include "internal/common.h"
#include "internal/ngx.h"
#include "internal/state.h"
#include "internal/timing.h"

#include <sl.h>
#include <sl_core_api.h>
#include <sl_dlss.h>
#include <sl_dlss_g.h>
#include <sl_pcl.h>

/*
 * The Streamline DLSS surface of the module, layered above state like the NGX unit. The ABI
 * keeps the NGX-valued parameters the rest of the module already validates against; the
 * mapping to sl:: types happens here, where the Streamline headers live.
 */
namespace mc_dlss {

bool sl_session_ready() noexcept {
    return g_state.streamlineInitialized && g_state.proxyDevice != 0;
}

void shutdown_streamline() noexcept {
    // The module gates every close-path shutdown on a ready session, so bootstrap has always
    // run by the time this is called; the guard keeps the unit self-contained for future
    // callers. The result is deliberately not acted on: on a device already lost there is
    // nothing left to salvage, and the teardown has to complete either way.
    if (g_state.streamlineInitialized) {
        slShutdown();
    }
}

int32_t query_optimal_dimensions_sl(const uint32_t outputWidth, const uint32_t outputHeight,
                                    const uint32_t qualityMode, uint32_t* renderWidth,
                                    uint32_t* renderHeight) noexcept {
    if (!sl_session_ready()) {
        return kNotInitialized;
    }
    if (renderWidth == nullptr || renderHeight == nullptr ||
        !valid_dimensions(outputWidth, outputHeight, outputWidth, outputHeight) ||
        !valid_quality_mode(qualityMode)) {
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

    // The NGX PerfQuality values are the sl::DLSSMode values shifted by one: MaxPerf=0 is
    // eMaxPerformance=1, Balanced=1 is eBalanced=2, MaxQuality=2 is eMaxQuality=3,
    // UltraPerformance=3 is eUltraPerformance=4, DLAA=5 is eDLAA=6 (eOff=0 is unreachable
    // because valid_quality_mode refused it above).
    sl::DLSSOptions options{};
    options.mode = static_cast<sl::DLSSMode>(qualityMode + 1);
    options.outputWidth = outputWidth;
    options.outputHeight = outputHeight;
    // Minecraft's pipeline is SDR and the engine supplies no exposure, so every option this
    // module records states both explicitly rather than inheriting the SDK defaults.
    options.colorBuffersHDR = sl::Boolean::eFalse;
    options.useAutoExposure = sl::Boolean::eFalse;
    options.alphaUpscalingEnabled = sl::Boolean::eFalse;

    sl::DLSSOptimalSettings settings{};
    const sl::Result result = slDLSSGetOptimalSettings(options, settings);
    if (result != sl::Result::eOk) {
        return static_cast<int32_t>(result);
    }
    if (!valid_dimensions(outputWidth, outputHeight, settings.optimalRenderWidth,
                          settings.optimalRenderHeight)) {
        return kInvalidParameter;
    }
    *renderWidth = settings.optimalRenderWidth;
    *renderHeight = settings.optimalRenderHeight;
    return kSuccess;
}

int32_t record_sr_options() noexcept {
    if (!sl_session_ready()) {
        return kNotInitialized;
    }

    sl::DLSSOptions options{};
    options.mode = static_cast<sl::DLSSMode>(g_state.qualityMode + 1);
    options.outputWidth = g_state.outputWidth;
    options.outputHeight = g_state.outputHeight;
    options.colorBuffersHDR = sl::Boolean::eFalse;
    options.useAutoExposure = sl::Boolean::eFalse;
    options.alphaUpscalingEnabled = sl::Boolean::eFalse;

    // The NGX preset values (J=10, K=11, L=12, M=13) are the sl::DLSSPreset values of the
    // same name, so the caller's value lands directly on the field sl::DLSSOptions carries
    // for the mode it is running. Every other preset field stays the SDK default.
    const sl::DLSSPreset preset = static_cast<sl::DLSSPreset>(g_state.renderPreset);
    switch (static_cast<NVSDK_NGX_PerfQuality_Value>(g_state.qualityMode)) {
        case NVSDK_NGX_PerfQuality_Value_MaxPerf:
            options.performancePreset = preset;
            break;
        case NVSDK_NGX_PerfQuality_Value_Balanced:
            options.balancedPreset = preset;
            break;
        case NVSDK_NGX_PerfQuality_Value_MaxQuality:
            options.qualityPreset = preset;
            break;
        case NVSDK_NGX_PerfQuality_Value_UltraPerformance:
            options.ultraPerformancePreset = preset;
            break;
        case NVSDK_NGX_PerfQuality_Value_DLAA:
            options.dlaaPreset = preset;
            break;
        // configure validated the mode before storing it, so this is unreachable; an
        // unrecognized mode records no preset rather than guessing at one.
        default:
            break;
    }

    const sl::Result result = slDLSSSetOptions(sl::ViewportHandle{0}, options);
    return result == sl::Result::eOk ? kSuccess : static_cast<int32_t>(result);
}

// The DLSS-G 2x option record, shared by mc_dlss_configure_fg (which stores it with the
// caller's back-buffer count) and the per-frame present handoff (which re-records it with
// the stored count). The record is fixed at the contract's single multiplier: 2x. Every
// field is stated explicitly rather than inherited from the SDK defaults, because each one
// is a decision the guide calls out - retained resources for seamless pause/menu
// suspension, UI recomposition for the split's separate HUD-less/UI inputs, and the
// Vulkan-only eBlockNoClientQueues queue-parallelism mode, which lets DLSS-G run on its own
// queues instead of blocking the presenting queue. The host's obligation under that mode -
// waiting on DLSSGState::inputsProcessingCompletionFence before modifying or destroying
// the tagged inputs of a previously presented frame - is the frame-side discipline the M-11
// present slice implements. The guide's set-options call validates little of this and the
// wrong form of any field records silently, so the record is a dense contract, not a
// convenience.
sl::DLSSGOptions make_fg_options(const uint32_t numBackBuffers) noexcept {
    sl::DLSSGOptions options{};
    options.mode = sl::DLSSGMode::eOn;
    options.numFramesToGenerate = 1;
    options.flags = sl::DLSSGFlags::eRetainResourcesWhenOff;
    options.queueParallelismMode = sl::DLSSGQueueParallelismMode::eBlockNoClientQueues;
    options.enableUserInterfaceRecomposition = sl::Boolean::eTrue;
    options.numBackBuffers = numBackBuffers;
    // The depth/motion inputs are render-sized and the colour backbuffer is output-sized, the
    // same size split the SR configuration already stores.
    options.mvecDepthWidth = g_state.renderWidth;
    options.mvecDepthHeight = g_state.renderHeight;
    options.colorWidth = g_state.outputWidth;
    options.colorHeight = g_state.outputHeight;
    // Minecraft's backbuffer, HUD-less composite input, and UI target are all RGBA8_UNORM;
    // the module's motion image is R16G16_SFLOAT; the depth is D32_SFLOAT. The formats are
    // the same ones the module's own images and the engine's targets already use, declared
    // here so the plugin allocates its internal resources against them.
    options.colorBufferFormat = VK_FORMAT_R8G8B8A8_UNORM;
    options.mvecBufferFormat = static_cast<uint32_t>(kMotionFormat);
    options.depthBufferFormat = VK_FORMAT_D32_SFLOAT;
    options.hudLessBufferFormat = VK_FORMAT_R8G8B8A8_UNORM;
    options.uiBufferFormat = VK_FORMAT_R8G8B8A8_UNORM;
    return options;
}

int32_t record_fg_options(const uint32_t numBackBuffers) noexcept {
    if (!sl_session_ready()) {
        return kNotInitialized;
    }
    // The record reads everything sized from the stored configuration, so a configuration
    // that never stored dimensions (no successful mc_dlss_configure) cannot be recorded for.
    if (!valid_dimensions(g_state.outputWidth, g_state.outputHeight, g_state.renderWidth,
                          g_state.renderHeight)) {
        return kInvalidParameter;
    }

    // The viewport is the same one the SR options, tags, and evaluation record against: the
    // frame's resources tag on viewport 0 and the options must name the viewport they apply
    // to.
    const sl::Result result = slDLSSGSetOptions(sl::ViewportHandle{0}, make_fg_options(numBackBuffers));
    if (result != sl::Result::eOk) {
        return static_cast<int32_t>(result);
    }
    // The record succeeded, so the stored configuration now has DLSS-G options the frame's
    // tag can gate on, and the back-buffer count those options were validated with is stored
    // for the per-frame handoff to re-record. reset_state clears both with the rest of the
    // struct, and a later mc_dlss_configure clears the flag when it replaces the
    // configuration those options were recorded against.
    g_state.fgOptionsRecorded = true;
    g_state.fgNumBackBuffers = numBackBuffers;
    return kSuccess;
}

// Drops the present-handoff eligibility of any in-flight frame: the retained Streamline
// frame token and the SR/FG tag records and indexes. Called wherever the frame those records
// name can no longer reach a present - configuration replacement, reset, image release, and
// a failed slSetTagForFrame - so a stale record can never satisfy a later handoff once the
// configuration it was recorded for was replaced, the frame's resources are gone, or the
// frame's tag call failed.
void invalidate_frame_eligibility() noexcept {
    // The retained token belongs to a frame whose records are being dropped with it: the
    // next tag must obtain a fresh token rather than advance the frame under a stale one.
    g_state.frameToken = nullptr;
    // Both tag records and their indexes clear together: a handoff reads the two sides as
    // one set, so one side can never outlive the other's invalidation.
    g_state.srTagFrameIndexRecorded = false;
    g_state.lastSrTagFrameIndex = 0;
    g_state.fgTagFrameIndexRecorded = false;
    g_state.lastFgTagFrameIndex = 0;
}

// Appends one marker this module actually emitted to the present-marker event log: the
// per-type count, the total count, and the ring slot. The ring keeps only the most recent
// events; the counts are cumulative, so the exactly-one-START-and-one-END-per-handoff oracle
// stays exact once the ring has wrapped.
static void record_present_marker_event(const PresentMarkerType type,
                                        const sl::FrameToken* frameToken) noexcept {
    const uint32_t frameIndex = static_cast<uint32_t>(*frameToken);
    g_state.presentMarkerLog[g_state.presentMarkerEventCount % kPresentMarkerLogSize] =
        PresentMarkerEvent{type, frameIndex};
    g_state.presentMarkerEventCount += 1;
    if (type == kPresentMarkerStart) {
        g_state.presentMarkerStartCount += 1;
    } else {
        g_state.presentMarkerEndCount += 1;
    }
}

int32_t record_present_handoff() noexcept {
    if (!sl_session_ready()) {
        return kNotInitialized;
    }
    // The handoff re-records the options a successful mc_dlss_configure_fg stored, so a
    // session whose options never recorded - or whose configuration was replaced since -
    // has nothing to hand off with. Same gate as the FG tag.
    if (!g_state.fgOptionsRecorded) {
        return kInvalidParameter;
    }
    // The FG tags reference the module's motion image; a handoff for a tag set whose motion
    // source no longer exists at the configured size would hand the present path a frame
    // that references a destroyed resource.
    if (!images_match_configuration()) {
        return kInvalidParameter;
    }
    // The present-time DLSS-G path reads the frame's SR and FG tags together, so the handoff
    // only accepts a frame both tag sets recorded under the same frame index. A missing
    // record is a partial tag set; unequal records are the stale frame the FG tag left
    // behind when it failed, or one side re-tagged without the other. The same check is also
    // the exactly-one-handoff rule: a successful handoff consumes the set by clearing both
    // sides' freshness flags, so a set that already handed off reads as two stale records
    // here. Each side's next successful tag record re-arms only its own half, and the set is
    // eligible again only when both halves are fresh once more - repeating one side alone
    // can never revive a consumed handoff while the counterpart is stale.
    if (!g_state.srTagFrameIndexRecorded || !g_state.fgTagFrameIndexRecorded) {
        return kInvalidParameter;
    }
    if (g_state.lastSrTagFrameIndex != g_state.lastFgTagFrameIndex) {
        return kInvalidParameter;
    }
    // A handoff without a retained frame token has no frame index the markers could be
    // emitted under and no present the re-recorded options could serve: the refusal lands
    // before the options re-record and before the marker calls dereference the token, so it
    // emits no markers and re-records nothing, like every refusal above. The gates above
    // make this unreachable in practice - a complete equal-index tag set is only ever
    // produced alongside a retained token, and every path that drops the token drops the tag
    // records with it - but the marker emission dereferences the token, so the check is the
    // guard that keeps a null token from ever reaching a marker call.
    if (g_state.frameToken == nullptr) {
        return kNotInitialized;
    }

    // Re-record the stored 2x options with the back-buffer count the configuration was
    // validated with: the guide requires slDLSSGSetOptions per frame, and the record must
    // not drift from the count mc_dlss_configure_fg accepted. Every refusal above returned
    // before this point, so a rejected handoff re-records nothing and clears nothing.
    const sl::Result result = slDLSSGSetOptions(sl::ViewportHandle{0}, make_fg_options(g_state.fgNumBackBuffers));
    if (result != sl::Result::eOk) {
        return static_cast<int32_t>(result);
    }

    // Emit the present bracket under the frame's retained token, PRESENT_START then
    // PRESENT_END. The DLSS-G guide requires the frame index carried by the Reflex present
    // markers to match the frame index carried by the common constants ("Make sure that
    // frame index provided with the common constants is matching the presented frame (i.e.
    // frame index provided with Reflex markers ReflexMarker::ePresentStart and
    // ReflexMarker::ePresentEnd)"); this module records the constants, the SR/FG tags, and
    // both markers against the same retained token, so all four name one frame index. The
    // DLSS-G plugin correlates the presented frame with its constants through PRESENT_START
    // and disables generation for the frame without it, so a frame whose markers could not
    // be emitted must not hand off.
    sl::FrameToken* frameToken = g_state.frameToken;
    const sl::Result startResult = slPCLSetMarker(sl::PCLMarker::ePresentStart, *frameToken);
    if (startResult != sl::Result::eOk) {
        // No marker reached the plugin and nothing was recorded: the frame's tag set and
        // retained token stay in place, so the caller may retry the handoff for the same
        // frame - a retry re-emits the first START, not a duplicate.
        return static_cast<int32_t>(startResult);
    }
    // The START reached the plugin under the frame's token: the event log records it
    // immediately, so a handoff whose END fails reads truthfully as one START event and no
    // END rather than as a pair that never happened.
    record_present_marker_event(kPresentMarkerStart, frameToken);
    const sl::Result endResult = slPCLSetMarker(sl::PCLMarker::ePresentEnd, *frameToken);
    if (endResult != sl::Result::eOk) {
        // The frame's PRESENT_START is already out to the plugin, so this frame must never
        // be presented through a retry: a retry would emit a second PRESENT_START for the
        // same frame (START, START, END), and the plugin correlates the presented frame
        // through the first. The handoff consumes the frame exactly as a successful one
        // would - clearing both tag sides' records and the retained token - so a later
        // handoff can only act on a fresh tag set under a fresh token. The pair is still
        // recorded only after the END succeeds: the log holds the START event, no END
        // event, and the END error returns.
        g_state.srTagFrameIndexRecorded = false;
        g_state.fgTagFrameIndexRecorded = false;
        g_state.frameToken = nullptr;
        return static_cast<int32_t>(endResult);
    }
    record_present_marker_event(kPresentMarkerEnd, frameToken);
    // The bracket recorded under the frame index the token names, for the present-marker
    // oracle: the event log answers the START and END events this handoff actually emitted,
    // in order, each under its frame index (which the handoff's own gates just proved equal
    // to the SR/FG tag indexes), and the per-type counts prove the exactly-once half - each
    // successful handoff adds exactly one START and one END. Recorded only after both
    // marker calls succeeded, so a handoff whose END marker failed claims no END event.
    g_state.srTagFrameIndexRecorded = false;
    g_state.fgTagFrameIndexRecorded = false;
    // The handoff is the composed frame's terminal act: it consumes the frame token the
    // evaluation retained for the FG re-declaration, so the next frame's tags obtain a fresh
    // token under a fresh index instead of re-recording over this frame's present-lifetime
    // records.
    g_state.frameToken = nullptr;
    return kSuccess;
}

int32_t query_present_markers(uint32_t* startCount, uint32_t* endCount, uint32_t* eventCount,
                              uint32_t* events, const uint32_t eventsCapacity) noexcept {
    if (startCount == nullptr || endCount == nullptr || eventCount == nullptr ||
        events == nullptr) {
        return kInvalidParameter;
    }
    // The oracle answers only once this session actually emitted a marker: before that
    // there is no event any marker was emitted under, and the refusal is exactly what makes
    // "refused or pre-ready handoffs emit no markers" observable to the test - a handoff
    // that leaked markers would populate the log before the test expects it to.
    if (g_state.presentMarkerEventCount == 0) {
        return kNotInitialized;
    }
    *startCount = g_state.presentMarkerStartCount;
    *endCount = g_state.presentMarkerEndCount;
    *eventCount = g_state.presentMarkerEventCount;
    // The ring holds the most recent min(eventCount, kPresentMarkerLogSize) events, oldest
    // first: the slot at eventCount % kPresentMarkerLogSize holds the oldest kept event (it
    // is the next to be overwritten), and the kept events follow it around the ring.
    const uint32_t kept = g_state.presentMarkerEventCount < kPresentMarkerLogSize
                              ? g_state.presentMarkerEventCount
                              : kPresentMarkerLogSize;
    const uint32_t copied = eventsCapacity < kept ? eventsCapacity : kept;
    const uint32_t oldest = (g_state.presentMarkerEventCount - kept) % kPresentMarkerLogSize;
    for (uint32_t i = 0; i < copied; ++i) {
        const PresentMarkerEvent& event =
            g_state.presentMarkerLog[(oldest + i) % kPresentMarkerLogSize];
        events[i * 2] = static_cast<uint32_t>(event.type);
        events[i * 2 + 1] = event.frameIndex;
    }
    return kSuccess;
}

// Blocks until the DLSS-G plugin's input-processing timeline semaphore reaches `value`, on
// the caller's thread and through the Vulkan device. `value` is the value the plugin
// reported under lastPresentInputsProcessingCompletionFenceValue for the inputs the last
// present consumed; the wait is for that exact value because the plugin signals the
// semaphore with the processing's completion value, and waiting for anything lower would
// let the caller reuse inputs the plugin still reads.
static int32_t wait_on_inputs_semaphore(const VkDevice device, const VkSemaphore semaphore,
                                        const uint64_t value) noexcept {
    // The plugin-internal fence the state reports is a Vulkan timeline semaphore: the wait
    // is the value-aware vkWaitSemaphores with VkSemaphoreWaitInfo, not vkWaitForFences,
    // which would hand a VkFence-typed call a VkSemaphore handle. The flags stay zero - the
    // wait is for the reported value to be reached, not for any value up to it - and the
    // timeout is infinite because the semaphore is signaled by GPU work that will complete:
    // a finite timeout would turn a transient stall into a latched fallback instead of a
    // wait.
    const VkSemaphoreWaitInfo waitInfo{
        VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO,
        nullptr,
        0,
        1,
        &semaphore,
        &value,
    };
    const VkResult wait = vkWaitSemaphores(device, &waitInfo, UINT64_MAX);
    return wait == VK_SUCCESS ? kSuccess : kFailure;
}

int32_t wait_fg_inputs_value(const uint64_t vkDevice, const uint64_t semaphore,
                             const uint64_t value) noexcept {
    // The wait needs both real handles: a null device or semaphore is a caller that did not
    // fill the call, and vkWaitSemaphores against either would be undefined behaviour.
    if (vkDevice == 0 || semaphore == 0) {
        return kInvalidParameter;
    }
    return wait_on_inputs_semaphore(from_uint64<VkDevice>(vkDevice),
                                    from_uint64<VkSemaphore>(semaphore), value);
}

int32_t wait_fg_inputs_idle() noexcept {
    // The wait protects the inputs of a presented DLSS-G frame, so a session that never
    // bootstrapped or never recorded a Vulkan device cannot answer it: same readiness gate
    // as every Streamline call. The device handle is required by vkWaitSemaphores itself, so
    // a session whose tuple mc_dlss_initialize never recorded is part of this refusal rather
    // than a semaphore wait against a null device.
    if (!sl_session_ready() || g_state.device == VK_NULL_HANDLE) {
        return kNotInitialized;
    }
    // The wait protects the inputs of a frame that was presented through DLSS-G, and the
    // options record is what names those inputs and their back-buffer count; a session whose
    // options never recorded has no presented frame whose input processing this call could
    // wait for. Same gate as the FG tag.
    if (!g_state.fgOptionsRecorded) {
        return kInvalidParameter;
    }

    // The semaphore and its value are read fresh per call: the plugin allocates the
    // semaphore lazily and signals it with each present's input-processing completion value,
    // so the state query is what tells this call whether there is anything to wait on and
    // under which value. The options argument stays null - the state this call needs is the
    // semaphore and the value, not a VRAM estimate, and the guide calls the estimate query
    // needlessly expensive per frame.
    sl::DLSSGState state{};
    const sl::Result result = slDLSSGGetState(sl::ViewportHandle{0}, state, nullptr);
    if (result != sl::Result::eOk) {
        return static_cast<int32_t>(result);
    }
    // A null semaphore means the plugin has no input processing in flight to wait for - the
    // typical case before the first present - and there is nothing this call could wait on,
    // so it is a no-op success rather than a refusal: the caller's frame may proceed.
    if (state.inputsProcessingCompletionFence == nullptr) {
        return kSuccess;
    }

    // The wait deliberately does not look at state.status: the status-to-off fallback is the
    // status-owning slice's job, and gating this wait on it would starve the very frame whose
    // inputs are still being read. The value is the one the plugin reported for the
    // previously presented frame's inputs, read from the same state query that delivered the
    // semaphore, so the two always travel together.
    return wait_on_inputs_semaphore(
        g_state.device,
        from_uint64<VkSemaphore>(static_cast<uint64_t>(reinterpret_cast<std::uintptr_t>(
            state.inputsProcessingCompletionFence))),
        state.lastPresentInputsProcessingCompletionFenceValue);
}

int32_t query_fg_state(uint32_t* status, uint32_t* numFramesPresented,
                       uint64_t* lastPresentInputsProcessingFenceValue,
                       uint64_t* inputsProcessingCompletionFence) noexcept {
    if (!sl_session_ready()) {
        return kNotInitialized;
    }
    // The state is the DLSS-G plugin's answer for the viewport the recorded options named,
    // so the same gates as the FG tag and the input wait hold: no options record means no
    // DLSS-G state exists to read. The status-to-off fallback is the status-owning slice's
    // job, not this read's; the read reports whatever status the plugin holds.
    if (!g_state.fgOptionsRecorded) {
        return kInvalidParameter;
    }
    // All four outputs are required: the read fills a caller-provided snapshot, and a null
    // output is a caller that did not fill the call - the same refusal as the other
    // out-parameter queries in the module.
    if (status == nullptr || numFramesPresented == nullptr ||
        lastPresentInputsProcessingFenceValue == nullptr ||
        inputsProcessingCompletionFence == nullptr) {
        return kInvalidParameter;
    }

    // The options argument stays null like the input wait's state query: the snapshot this
    // read needs is the status, the presented-frame counter, and the input fence + value,
    // not a VRAM estimate, and the guide calls the estimate query needlessly expensive.
    sl::DLSSGState state{};
    const sl::Result result = slDLSSGGetState(sl::ViewportHandle{0}, state, nullptr);
    if (result != sl::Result::eOk) {
        return static_cast<int32_t>(result);
    }
    // The status is reported as the raw word: eDLSSGStatusOk is zero and every failure bit
    // is its own mask, so the snapshot carries the word for the caller to compare against
    // the enum's bit vocabulary rather than a boolean that would hide which bit is set.
    *status = static_cast<uint32_t>(state.status);
    // The counter counts presents since the previous state query, so each read resets it; a
    // caller that wants a window between two reads must read once before it and once after.
    *numFramesPresented = state.numFramesActuallyPresented;
    // The fence and its value travel together exactly as the input wait reads them, so the
    // snapshot a caller takes can be handed to wait_fg_inputs_value unchanged.
    *lastPresentInputsProcessingFenceValue = state.lastPresentInputsProcessingCompletionFenceValue;
    *inputsProcessingCompletionFence = reinterpret_cast<uint64_t>(state.inputsProcessingCompletionFence);
    return kSuccess;
}

// Builds the sl::Resource description for one tagged image. `native` is the VkImage and `view`
// the VkImageView the ABI carried (or this module allocated), `state` is the layout the
// feature reads or writes the image in (the layout this module's own transitions establish
// immediately before it), and `width`/`height`/`format` are the dimensions and format the tag
// names - the configured render size for the inputs, the output size for the output. All four
// resources are single-level, single-layer 2D images.
sl::Resource make_tagged_resource(void* native, void* memory, void* view, uint32_t state,
                                  uint32_t width, uint32_t height, uint32_t format) noexcept {
    sl::Resource resource(sl::ResourceType::eTex2d, native, memory, view, state);
    resource.width = width;
    resource.height = height;
    resource.nativeFormat = format;
    resource.mipLevels = 1;
    resource.arrayLayers = 1;
    resource.usage = 0;
    // `flags` is the one Resource field without a default member initializer in the SDK
    // header, so it is set explicitly rather than trusted to be zero.
    resource.flags = 0;
    return resource;
}

// Records the frame's DLSS SR evaluation on the caller's command buffer, consuming the frame
// token mc_dlss_tag_sr_resources obtained and retained for this frame. The plugin needs the
// per-frame constants or its begin event fails (sl.dlss returns eErrorMissingConstants), and
// the frame token chained through slSetConstants and slEvaluateFeature must be the one the
// frame's resources were tagged with, so both record against the retained token.
// The module's motion image is the one evaluation input the caller's restore does not cover:
// mc_dlss_evaluate returns the engine's colour and depth to where Minecraft expects them but
// leaves the motion image in the read state, and the SR-only path never read it again until
// the next frame's transitions. The composed present-driven frame reads it differently: the
// FG tag declared the motion image in the engine-resting layout (kEngineRestingLayout) for
// its whole valid-until-present lifetime, so the evaluation has to end with the image
// actually resting in GENERAL - the composed frame must leave every FG-tagged resource in
// the layout its tag declared, and the module's own image keeps the same between-frame
// discipline the motion pass documents. The restore runs whether or not the evaluation
// succeeded, matching the caller's own restore discipline for the engine's images.
static void restore_motion_to_engine_resting_layout(const VkCommandBuffer commandBuffer) noexcept {
    const uint64_t motionImage = to_uint64(g_state.motionImage.image);
    record_layout_transition(commandBuffer, g_state.motionImage.image, image_range_of(false),
                             current_layout_of(motionImage), kEngineRestingLayout);
    note_layout_after_transition(motionImage, kEngineRestingLayout);
}

int32_t record_sr_evaluation(const McDlssEvaluateInfo& info,
                             VkCommandBuffer commandBuffer) noexcept {
    sl::FrameToken* frameToken = g_state.frameToken;
    if (frameToken == nullptr) {
        // Production always tags the frame's resources before evaluating them; a frame that
        // never tagged has no token for the evaluation to record against.
        return kNotInitialized;
    }

    sl::Constants constants{};
    // The jitter offset is pixel space, the unit the engine's sequence is in and the unit the
    // plugin passes through to NGX unchanged.
    constants.jitterOffset = sl::float2(info.jitter.x, info.jitter.y);
    // The module's motion pass writes NDC displacement. NDC spans two units edge-to-edge, so
    // half normalizes that displacement to screen-width units. Streamline then multiplies this
    // value by render width/height when it derives NGX's pixel-space MV scale; using one here
    // doubles every vector and produces the radial smear visible during camera translation.
    constants.mvecScale = sl::float2(0.5f, 0.5f);
    constants.reset = info.reset_history != 0 ? sl::Boolean::eTrue : sl::Boolean::eFalse;
    // The motion pass writes camera motion (mc_dlss_motion.comp reprojects through the camera),
    // so the plugin must not compute its own: with eTrue it does not read the camera matrices.
    constants.cameraMotionIncluded = sl::Boolean::eTrue;
    // The buffer is a 2D screen-space vector field, not a 3D one.
    constants.motionVectors3D = sl::Boolean::eFalse;
    // Minecraft 26.2 renders reversed-Z - DepthStencilState.DEFAULT tests GREATER_THAN_OR_EQUAL,
    // so 1.0 is the near plane and 0.0 the far plane and a cleared buffer reads 0.0 - which is
    // exactly the "value closer to the camera is higher" case. The NGX-era feature creation
    // told NGX the same thing through NVSDK_NGX_DLSS_Feature_Flags_DepthInverted.
    constants.depthInverted = sl::Boolean::eTrue;

    sl::Result result = slSetConstants(constants, *frameToken, sl::ViewportHandle{0});
    if (result != sl::Result::eOk) {
        // A failed frame has no history the next one could reuse, so the SR-only frame
        // consumes its token here. The composed frame keeps it instead: its FG tag
        // re-declares the shared inputs in the engine-resting layout after the evaluation,
        // and the present handoff consumes the token when it accepts the frame.
        if (!g_state.fgTagFrameIndexRecorded) {
            g_state.frameToken = nullptr;
        }
        // The evaluation never recorded, but the caller's transitions above still moved the
        // motion image into the read state before this call; the frame's tags survive a
        // failed evaluation, so the image goes back to the layout its FG tag declared rather
        // than being left for the present path to find in the wrong state.
        restore_motion_to_engine_resting_layout(commandBuffer);
        return static_cast<int32_t>(result);
    }

    // The viewport handle is chained into the evaluate inputs: the common plugin reads the
    // viewport id from there and refuses an evaluate that does not chain it.
    sl::ViewportHandle viewport{0};
    const sl::BaseStructure* inputs[] = {&viewport};
    // sl::CommandBuffer is void, so CommandBuffer* is the native Vulkan handle itself as
    // void*. Applying address-of here would pass the address of this local handle variable; SL
    // would forward that stack address to NGX as a VkCommandBuffer and corrupt the process.
    result = slEvaluateFeature(sl::kFeatureDLSS, *frameToken, inputs, 1, commandBuffer);
    // The SR-only frame consumed its token whether the evaluation succeeded or failed: a
    // failed frame has no history the next one could reuse, and the next tag must obtain a
    // fresh token. The composed frame keeps it instead: its FG tag re-declares the shared
    // depth and motion slots in the engine-resting layout after the evaluation (the
    // declaration the present path reads), and the present handoff consumes the token when
    // it accepts the frame.
    if (!g_state.fgTagFrameIndexRecorded) {
        g_state.frameToken = nullptr;
    }
    // The composed frame's FG tag declared the motion image GENERAL for its whole lifetime,
    // so the evaluation must leave it there - on the success path exactly as on the
    // constants-failure path above.
    restore_motion_to_engine_resting_layout(commandBuffer);
    return result == sl::Result::eOk ? kSuccess : static_cast<int32_t>(result);
}

int32_t tag_sr_resources(const McDlssTagInfo& info) noexcept {
    if (!sl_session_ready()) {
        return kNotInitialized;
    }
    if (info.command_buffer == 0 || !valid_image(info.color) || !valid_image(info.depth)) {
        return kInvalidParameter;
    }
    const VkCommandBuffer commandBuffer = from_uint64<VkCommandBuffer>(info.command_buffer);

    // The frame token is obtained once per frame: this call retains it for the evaluation to
    // consume, so a repeated tag for the same frame reuses the token instead of advancing the
    // frame the tags belong to.
    sl::Result result = sl::Result::eOk;
    if (g_state.frameToken == nullptr) {
        result = slGetNewFrameToken(g_state.frameToken);
        if (result != sl::Result::eOk) {
            return static_cast<int32_t>(result);
        }
    }
    sl::FrameToken* frameToken = g_state.frameToken;

    // The engine's colour and depth are the frame's inputs, so they tag from the first frame
    // on. The motion source is always the module's own motion image - filled by the compute
    // writer on the camera-only route and by the sentinel fill on the velocity route - so
    // direct companion tagging is retired and no engine velocity image crosses the ABI. The
    // module's output image can only tag once it exists at the configured size - there is no
    // NGX initialize in the SL path to acquire it early - so it is added when (and only
    // when) images_match_configuration holds.
    const bool moduleImagesReady = images_match_configuration();
    // Four slots when the module's images exist at the configured size (the two engine
    // inputs, the module motion image, the module output image), two otherwise.
    const uint32_t numTags = moduleImagesReady ? 4 : 2;

    const uint32_t renderWidth = g_state.renderWidth;
    const uint32_t renderHeight = g_state.renderHeight;
    const uint32_t outputWidth = g_state.outputWidth;
    const uint32_t outputHeight = g_state.outputHeight;

    // The declared states name where the images are when the evaluation reads them, not where
    // they rest when the frame is tagged. Manual-hooking Streamline transitions every tagged
    // resource at evaluation from the declared state, and this module's own transitions put the
    // engine inputs and the motion image into kDlssInputLayout and the output into
    // kDlssOutputLayout on the same recording immediately before the evaluation. Declaring the
    // resting layouts instead would make the plugin record a barrier whose oldLayout no longer
    // matches the image's actual layout, which validation rejects.
    sl::Resource colorResource = make_tagged_resource(
        from_uint64<void*>(info.color.image), nullptr, from_uint64<void*>(info.color.view),
        kDlssInputLayout, renderWidth, renderHeight, info.color.format);
    sl::Resource depthResource = make_tagged_resource(
        from_uint64<void*>(info.depth.image), nullptr, from_uint64<void*>(info.depth.view),
        kDlssInputLayout, renderWidth, renderHeight, info.depth.format);
    sl::Resource motionResource = make_tagged_resource(
        reinterpret_cast<void*>(g_state.motionImage.image),
        reinterpret_cast<void*>(g_state.motionImage.memory),
        reinterpret_cast<void*>(g_state.motionImage.view),
        kDlssInputLayout, renderWidth, renderHeight,
        static_cast<uint32_t>(kMotionFormat));
    sl::Resource outputResource = make_tagged_resource(
        reinterpret_cast<void*>(g_state.outputImage.image),
        reinterpret_cast<void*>(g_state.outputImage.memory),
        reinterpret_cast<void*>(g_state.outputImage.view),
        kDlssOutputLayout, outputWidth, outputHeight,
        static_cast<uint32_t>(kOutputFormat));

    // Each resource chains the subresource range its role names. The plugin derives the NGX
    // resource's range from the tag and defaults to a colour aspect when none is chained, which
    // would hand NGX a colour-aspect depth image; the ranges are file-static because the plugin
    // reads them when it builds the NGX resources at evaluation, after this call has returned.
    static sl::SubresourceRange colorRange{};
    colorRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    colorRange.baseMipLevel = 0;
    colorRange.levelCount = 1;
    colorRange.baseArrayLayer = 0;
    colorRange.layerCount = 1;
    static sl::SubresourceRange depthRange{};
    depthRange.aspectMask = VK_IMAGE_ASPECT_DEPTH_BIT;
    depthRange.baseMipLevel = 0;
    depthRange.levelCount = 1;
    depthRange.baseArrayLayer = 0;
    depthRange.layerCount = 1;
    static sl::SubresourceRange motionRange{};
    motionRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    motionRange.baseMipLevel = 0;
    motionRange.levelCount = 1;
    motionRange.baseArrayLayer = 0;
    motionRange.layerCount = 1;
    static sl::SubresourceRange outputRange{};
    outputRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    outputRange.baseMipLevel = 0;
    outputRange.levelCount = 1;
    outputRange.baseArrayLayer = 0;
    outputRange.layerCount = 1;
    colorResource.next = &colorRange;
    depthResource.next = &depthRange;
    motionResource.next = &motionRange;
    outputResource.next = &outputRange;

    // Every tag covers the whole image: the inputs are the configured render size, the output
    // is the configured output size, and all four start at the origin.
    const sl::Extent renderExtent{0, 0, renderWidth, renderHeight};
    const sl::Extent outputExtent{0, 0, outputWidth, outputHeight};
    sl::ResourceTag tags[4]{};
    tags[0] = sl::ResourceTag(&colorResource, sl::kBufferTypeScalingInputColor,
                              sl::ResourceLifecycle::eValidUntilPresent, &renderExtent);
    tags[1] = sl::ResourceTag(&depthResource, sl::kBufferTypeDepth,
                              sl::ResourceLifecycle::eValidUntilPresent, &renderExtent);
    if (moduleImagesReady) {
        // The motion source is the module's own motion image on every route, filled with the
        // same NDC motion payload the evaluation's mvecScale and cameraMotionIncluded expect.
        tags[2] = sl::ResourceTag(&motionResource, sl::kBufferTypeMotionVectors,
                                  sl::ResourceLifecycle::eValidUntilPresent, &renderExtent);
        tags[3] = sl::ResourceTag(&outputResource, sl::kBufferTypeScalingOutputColor,
                                  sl::ResourceLifecycle::eValidUntilPresent, &outputExtent);
    }

    // The command buffer is the caller's shared recording: slSetTagForFrame takes it as an
    // opaque pointer and this module only ever records on it, never submits.
    // As above, pass the VkCommandBuffer handle, not the address of the local handle variable.
    result = slSetTagForFrame(*frameToken, sl::ViewportHandle{0}, tags, numTags, commandBuffer);
    if (result != sl::Result::eOk) {
        // A failed tag leaves the frame with no valid SR record, and the token the attempt
        // used is not a token any later record may be reused under: the whole in-flight set
        // drops with the token, so neither this call's half-records nor the counterpart
        // tag's records can satisfy a later handoff against a fresh token.
        invalidate_frame_eligibility();
        return static_cast<int32_t>(result);
    }
    // The frame index this call tagged under, recorded for the composed-rung oracle
    // (mc_dlss_query_tagged_frame_indexes): the test asserts the SR and FG tags of one frame
    // landed under the same index. Recorded only after the tag succeeded, so a failed tag
    // claims nothing; the index is read from the token itself, so a second tag of the same
    // frame re-records the same index.
    g_state.srTagFrameIndexRecorded = true;
    g_state.lastSrTagFrameIndex = static_cast<uint32_t>(*frameToken);
    // The record marks only the SR side of the tag set fresh: a set a handoff consumed stays
    // consumed until the FG side also records, so repeating only this tag can never revive
    // eligibility whose counterpart is still stale. In production every frame re-tags both
    // sides under a fresh token, so per-side freshness and a per-frame re-arm coincide.
    return kSuccess;
}

int32_t tag_fg_resources(const McDlssFgTagInfo& info) noexcept {
    if (!sl_session_ready()) {
        return kNotInitialized;
    }
    if (info.command_buffer == 0 || !valid_image(info.depth) || !valid_image(info.hudless) ||
        !valid_image(info.ui)) {
        return kInvalidParameter;
    }
    // The tagged formats must be exactly the ones the FG options recorded: the plugin
    // allocates its internal resources against the option-declared formats, and a tag that
    // names a different format hands it a resource whose description disagrees with its
    // allocation. Each check names the option field the tag must match (depthBufferFormat,
    // hudLessBufferFormat, uiBufferFormat).
    if (info.depth.format != static_cast<uint32_t>(VK_FORMAT_D32_SFLOAT) ||
        info.hudless.format != static_cast<uint32_t>(VK_FORMAT_R8G8B8A8_UNORM) ||
        info.ui.format != static_cast<uint32_t>(VK_FORMAT_R8G8B8A8_UNORM)) {
        return kInvalidParameter;
    }
    const VkCommandBuffer commandBuffer = from_uint64<VkCommandBuffer>(info.command_buffer);

    // The tag is only meaningful against the configuration the frame was recorded for: the
    // DLSS-G options must have recorded successfully for the stored configuration (a tag
    // before mc_dlss_configure_fg succeeded names resources no options interpret), and the
    // module's motion image must exist at the configured size (there is nothing to tag as
    // the motion source before mc_dlss_acquire_images). Both are fixed before the first
    // frame can be tagged; either missing is a caller out of order, not a frame to skip, so
    // the call refuses instead of submitting a partial tag set.
    if (!g_state.fgOptionsRecorded || !images_match_configuration()) {
        return kInvalidParameter;
    }

    // The frame token is shared with the SR tag for the same frame: whichever tag records
    // first obtains it, the other reuses it, and the frame's evaluation and present handoff
    // consume it between them. Reusing rather than advancing keeps every tag of one frame on
    // one frame index, which is what the present-time DLSS-G evaluation reads them under; the
    // composed frame's second FG call after the evaluation reuses the retained token the same
    // way, so its re-declaration lands on the same frame index too.
    sl::Result result = sl::Result::eOk;
    if (g_state.frameToken == nullptr) {
        result = slGetNewFrameToken(g_state.frameToken);
        if (result != sl::Result::eOk) {
            return static_cast<int32_t>(result);
        }
    }
    sl::FrameToken* frameToken = g_state.frameToken;

    const uint32_t renderWidth = g_state.renderWidth;
    const uint32_t renderHeight = g_state.renderHeight;
    const uint32_t outputWidth = g_state.outputWidth;
    const uint32_t outputHeight = g_state.outputHeight;

    // The declared state is the layout the images rest in when the frame is tagged: Minecraft
    // rests every texture in GENERAL and the motion fill leaves the module's image in GENERAL,
    // and nothing in this call transitions them. SL reads the tagged resources at present
    // time, and the guide requires the declared state to be the layout they are in then; the
    // evaluation and present slices own the transitions that move the inputs before DLSS-G
    // reads them and must update these declared states with them. The composed frame does
    // exactly that with its second call to this function: the SR evaluation records between
    // the two calls, so the first call's shared depth and motion declarations are what the
    // evaluation overwrites with its own SHADER_READ_ONLY records and the second call
    // re-declares the slots in the engine-resting layout the images actually rest in after
    // the evaluation's restore - the declaration the present path reads.
    sl::Resource depthResource = make_tagged_resource(
        from_uint64<void*>(info.depth.image), nullptr, from_uint64<void*>(info.depth.view),
        kEngineRestingLayout, renderWidth, renderHeight, info.depth.format);
    sl::Resource hudlessResource = make_tagged_resource(
        from_uint64<void*>(info.hudless.image), nullptr, from_uint64<void*>(info.hudless.view),
        kEngineRestingLayout, outputWidth, outputHeight, info.hudless.format);
    sl::Resource uiResource = make_tagged_resource(
        from_uint64<void*>(info.ui.image), nullptr, from_uint64<void*>(info.ui.view),
        kEngineRestingLayout, outputWidth, outputHeight, info.ui.format);
    sl::Resource motionResource = make_tagged_resource(
        reinterpret_cast<void*>(g_state.motionImage.image),
        reinterpret_cast<void*>(g_state.motionImage.memory),
        reinterpret_cast<void*>(g_state.motionImage.view),
        kEngineRestingLayout, renderWidth, renderHeight,
        static_cast<uint32_t>(kMotionFormat));

    // Each resource chains the subresource range its role names. The plugin derives the NGX
    // resource's range from the tag and defaults to a colour aspect when none is chained, which
    // would hand NGX a colour-aspect depth image; the ranges are file-static because the plugin
    // reads them when it builds the NGX resources, after this call has returned.
    static sl::SubresourceRange depthRange{};
    depthRange.aspectMask = VK_IMAGE_ASPECT_DEPTH_BIT;
    depthRange.baseMipLevel = 0;
    depthRange.levelCount = 1;
    depthRange.baseArrayLayer = 0;
    depthRange.layerCount = 1;
    static sl::SubresourceRange hudlessRange{};
    hudlessRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    hudlessRange.baseMipLevel = 0;
    hudlessRange.levelCount = 1;
    hudlessRange.baseArrayLayer = 0;
    hudlessRange.layerCount = 1;
    static sl::SubresourceRange uiRange{};
    uiRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    uiRange.baseMipLevel = 0;
    uiRange.levelCount = 1;
    uiRange.baseArrayLayer = 0;
    uiRange.layerCount = 1;
    static sl::SubresourceRange motionRange{};
    motionRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    motionRange.baseMipLevel = 0;
    motionRange.levelCount = 1;
    motionRange.baseArrayLayer = 0;
    motionRange.layerCount = 1;
    depthResource.next = &depthRange;
    hudlessResource.next = &hudlessRange;
    uiResource.next = &uiRange;
    motionResource.next = &motionRange;

    // Every tag covers the whole image: the depth and motion inputs are the configured render
    // size, the HUD-less and UI buffers the configured output size, and all four start at the
    // origin. The HUD-less and UI buffers are output-sized because DLSS-G composites the
    // generated frame against them at present time.
    const sl::Extent renderExtent{0, 0, renderWidth, renderHeight};
    const sl::Extent outputExtent{0, 0, outputWidth, outputHeight};
    sl::ResourceTag tags[4]{};
    tags[0] = sl::ResourceTag(&depthResource, sl::kBufferTypeDepth,
                              sl::ResourceLifecycle::eValidUntilPresent, &renderExtent);
    tags[1] = sl::ResourceTag(&hudlessResource, sl::kBufferTypeHUDLessColor,
                              sl::ResourceLifecycle::eValidUntilPresent, &outputExtent);
    tags[2] = sl::ResourceTag(&uiResource, sl::kBufferTypeUIColorAndAlpha,
                              sl::ResourceLifecycle::eValidUntilPresent, &outputExtent);
    // The motion source is the module's own motion image on every route, filled with the
    // same NDC motion payload the DLSS-G evaluation expects; the pre-checks above already
    // refused the call until that image exists at the configured size, so all four tags
    // always record together.
    tags[3] = sl::ResourceTag(&motionResource, sl::kBufferTypeMotionVectors,
                              sl::ResourceLifecycle::eValidUntilPresent, &renderExtent);

    // The command buffer is the caller's shared recording: slSetTagForFrame takes it as an
    // opaque pointer and this module only ever records on it, never submits.
    // As above, pass the VkCommandBuffer handle, not the address of the local handle variable.
    result = slSetTagForFrame(*frameToken, sl::ViewportHandle{0}, tags, 4, commandBuffer);
    if (result != sl::Result::eOk) {
        // A failed tag leaves the frame with no valid FG record, and the token the attempt
        // used is not a token any later record may be reused under: the whole in-flight set
        // drops with the token, so neither this call's half-records nor the counterpart
        // tag's records can satisfy a later handoff against a fresh token. On the composed
        // frame this call is the post-evaluation re-declaration too, whose failure must drop
        // the SR record and token it re-recorded against exactly as any other tag failure
        // does.
        invalidate_frame_eligibility();
        return static_cast<int32_t>(result);
    }
    // The frame index this call tagged under, recorded for the composed-rung oracle
    // (mc_dlss_query_tagged_frame_indexes): the test asserts the SR and FG tags of one frame
    // landed under the same index, which is exactly the token-reuse behaviour this function
    // documents. Recorded only after the tag succeeded, so a failed tag claims nothing; the
    // index is read from the token itself, so when the SR tag retained the token this call
    // re-records the same index instead of a later one.
    g_state.fgTagFrameIndexRecorded = true;
    g_state.lastFgTagFrameIndex = static_cast<uint32_t>(*frameToken);
    // Same per-side freshness as the SR tag: the record marks only the FG side fresh, and a
    // set a handoff consumed stays consumed until the SR side also records.
    return kSuccess;
}

} // namespace mc_dlss
