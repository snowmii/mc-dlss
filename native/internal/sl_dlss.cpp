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
#include <sl_reflex.h>

#include <atomic>
#include <windows.h>

/*
 * The Streamline DLSS surface of the module, layered above state like the NGX unit. The ABI
 * keeps the NGX-valued parameters the rest of the module already validates against; the
 * mapping to sl:: types happens here, where the Streamline headers live.
 */
namespace mc_dlss {

namespace {

HWND g_pclWindow = nullptr;
WNDPROC g_previousWindowProc = nullptr;
std::atomic_uint32_t g_pclStatsMessage{0};
std::atomic_bool g_pclPingPending{false};

LRESULT CALLBACK pcl_window_proc(HWND window, UINT message, WPARAM wParam, LPARAM lParam) {
    const uint32_t statsMessage = g_pclStatsMessage.load(std::memory_order_relaxed);
    if (statsMessage != 0 && message == statsMessage) {
        g_pclPingPending.store(true, std::memory_order_release);
    }
    return g_previousWindowProc != nullptr
               ? CallWindowProcW(g_previousWindowProc, window, message, wParam, lParam)
               : DefWindowProcW(window, message, wParam, lParam);
}

void refresh_pcl_stats_message() noexcept {
    sl::PCLState state{};
    if (slPCLGetState(state) == sl::Result::eOk) {
        g_pclStatsMessage.store(state.statsWindowMessage, std::memory_order_relaxed);
    }
}

} // namespace

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

    const sl::Result result = slDLSSSetOptions(sl::ViewportHandle{kSrViewportId}, options);
    return result == sl::Result::eOk ? kSuccess : static_cast<int32_t>(result);
}

// The DLSS-G option record, shared by mc_dlss_configure_fg (which stores it with the
// caller's back-buffer count), the per-frame present handoff (which re-records it with
// the stored count), the mode record, and the multiplier record. The multiplier field
// carries the stored numFramesToGenerate (1 = 2x by default, cycled through
// record_fg_multiplier up to the device ceiling). Every
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
sl::DLSSGOptions make_fg_options(const uint32_t numBackBuffers,
                                  const sl::DLSSGMode mode) noexcept {
    sl::DLSSGOptions options{};
    options.mode = mode;
    // The stored multiplier: 1 (2x) from the default record, up to the device ceiling
    // numFramesToGenerateMax after a multiplier cycle. The per-frame handoff and the mode
    // switch re-record the same stored value, so the options can never drift from the
    // configuration the session cycles.
    options.numFramesToGenerate = g_state.fgNumFramesToGenerate;
    options.flags = sl::DLSSGFlags::eRetainResourcesWhenOff;
    // eBlockPresentingClientQueue, the SDK default, rather than the Vulkan-only
    // eBlockNoClientQueues this recorded first. The parallel mode's gains are documented for
    // "GPU-limited applications having workload types employing multiple queues for
    // submissions"; Minecraft renders and presents on one queue, so there is no other queue's
    // work to overlap with DLSS-G's and nothing to gain - while the mode's cost is unavoidable:
    // on Vulkan it makes the wait on DLSSGState::inputsProcessingCompletionFence *always
    // required*, and that wait measured at 10-11ms of every 13ms app frame, which is the base
    // frame rate collapsing from ~450fps to ~75 with FG on. Under this mode the wait is only
    // "recommended but not required" when the tagged inputs are modified on the presenting
    // queue, which is the only queue Minecraft has - so the host-side wait goes away with it.
    options.queueParallelismMode = sl::DLSSGQueueParallelismMode::eBlockPresentingClientQueue;
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

    // The viewport is the FG-only one, distinct from the SR viewport the DLSS options, SR
    // tags, and evaluation record against: the frame's FG resources tag on the FG viewport
    // and the FG options must name the same viewport they apply to, so the orientation
    // flips a later slice applies to the FG side can never reach what SR reads.
    const sl::Result result = slDLSSGSetOptions(sl::ViewportHandle{kFgViewportId}, make_fg_options(numBackBuffers, sl::DLSSGMode::eOn));
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

int32_t record_fg_mode(const uint32_t fgEnabled) noexcept {
    if (!sl_session_ready()) {
        return kNotInitialized;
    }
    // The mode record is a mode switch on an existing DLSS-G options record: nothing is
    // recorded for a configuration that never validated its dimensions, and an eOff record
    // for a session whose options never recorded has no record to switch - the stored
    // back-buffer count is what the switched record reuses, and only a successful
    // mc_dlss_configure_fg stored it.
    if (!g_state.fgOptionsRecorded || !valid_dimensions(g_state.outputWidth, g_state.outputHeight,
                                                        g_state.renderWidth, g_state.renderHeight)) {
        return kInvalidParameter;
    }

    // The eOff record is the status-latch fallback's native half: mode eOff with the
    // retained-resources flag keeps the plugin's allocations alive for the session while it
    // stops interpolating, and the same back-buffer count the validated eOn record used
    // keeps the record's shape identical apart from the mode. eOn is the same call for the
    // ABI's symmetry; the per-frame handoff re-records eOn with the stored count anyway.
    const sl::DLSSGMode mode = fgEnabled != 0 ? sl::DLSSGMode::eOn : sl::DLSSGMode::eOff;
    const sl::Result result = slDLSSGSetOptions(sl::ViewportHandle{kFgViewportId},
                                                make_fg_options(g_state.fgNumBackBuffers, mode));
    return result == sl::Result::eOk ? kSuccess : static_cast<int32_t>(result);
}

int32_t record_fg_multiplier(const uint32_t numFramesToGenerate) noexcept {
    if (!sl_session_ready()) {
        return kNotInitialized;
    }
    // The multiplier record is a field change on an existing options record, so the same
    // gates as the mode record: nothing is recorded for a configuration that never
    // validated its dimensions or whose options never recorded.
    if (!g_state.fgOptionsRecorded || !valid_dimensions(g_state.outputWidth, g_state.outputHeight,
                                                        g_state.renderWidth, g_state.renderHeight)) {
        return kInvalidParameter;
    }
    // The device ceiling is read fresh from the plugin: numFramesToGenerateMax names the
    // largest multiplier the current system can generate, and a value below the 2x floor
    // or above the ceiling is refused here rather than recorded into options the plugin
    // would silently misread. The same GetState call shape the state read uses; the
    // options argument stays null like it does there.
    sl::DLSSGState state{};
    const sl::Result stateResult = slDLSSGGetState(sl::ViewportHandle{kFgViewportId}, state, nullptr);
    if (stateResult != sl::Result::eOk) {
        return static_cast<int32_t>(stateResult);
    }
    if (numFramesToGenerate < 1 || numFramesToGenerate > state.numFramesToGenerateMax) {
        return kInvalidParameter;
    }
    // The record itself: the same shape as the validated eOn record - mode, retained
    // resources, back-buffer count, extents, formats - with only numFramesToGenerate
    // changed. The stored value is written before the record so make_fg_options reads it,
    // and restored on failure, so a refused record leaves the stored configuration and the
    // plugin's options exactly as they were.
    const uint32_t previous = g_state.fgNumFramesToGenerate;
    g_state.fgNumFramesToGenerate = numFramesToGenerate;
    const sl::Result result = slDLSSGSetOptions(
        sl::ViewportHandle{kFgViewportId}, make_fg_options(g_state.fgNumBackBuffers, sl::DLSSGMode::eOn));
    if (result != sl::Result::eOk) {
        g_state.fgNumFramesToGenerate = previous;
        return static_cast<int32_t>(result);
    }
    return kSuccess;
}

int32_t query_fg_multiplier(uint32_t* current, uint32_t* max) noexcept {
    if (!sl_session_ready()) {
        return kNotInitialized;
    }
    // The stored multiplier is the recorded options', so the same gate as the state read:
    // no options record means no multiplier to report.
    if (!g_state.fgOptionsRecorded) {
        return kInvalidParameter;
    }
    if (current == nullptr || max == nullptr) {
        return kInvalidParameter;
    }
    sl::DLSSGState state{};
    const sl::Result result = slDLSSGGetState(sl::ViewportHandle{kFgViewportId}, state, nullptr);
    if (result != sl::Result::eOk) {
        return static_cast<int32_t>(result);
    }
    *current = g_state.fgNumFramesToGenerate;
    *max = state.numFramesToGenerateMax;
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
    g_state.presentTokenArmed = false;
    g_state.presentStartEmitted = false;
    // Both tag records and their indexes clear together: a handoff reads the two sides as
    // one set, so one side can never outlive the other's invalidation.
    g_state.srTagFrameIndexRecorded = false;
    g_state.lastSrTagFrameIndex = 0;
    g_state.fgTagFrameIndexRecorded = false;
    g_state.lastFgTagFrameIndex = 0;
    // The FG orientation copies were recorded under the dropped frame's token, so the gate
    // drops with them: the next frame's first FG tag must rebuild the copies under its own
    // token rather than skip them as the stale frame's second call.
    g_state.fgCopiesRecorded = false;
    g_state.fgCopiedFrameIndex = 0;
}

int32_t record_present_handoff() noexcept {
    if (!sl_session_ready()) return kNotInitialized;
    if (!g_state.fgOptionsRecorded || !images_match_configuration()) return kInvalidParameter;
    if (!g_state.srTagFrameIndexRecorded || !g_state.fgTagFrameIndexRecorded ||
        g_state.lastSrTagFrameIndex != g_state.lastFgTagFrameIndex) return kInvalidParameter;
    if (g_state.frameToken == nullptr) return kNotInitialized;
    const sl::Result result = slDLSSGSetOptions(
        sl::ViewportHandle{kFgViewportId}, make_fg_options(g_state.fgNumBackBuffers, sl::DLSSGMode::eOn));
    if (result != sl::Result::eOk) return static_cast<int32_t>(result);
    // The handoff consumes the tag set's handoff eligibility exactly as the present-time
    // design always did: both sides' records clear, so a second handoff for the same set
    // refuses and a partial re-tag can never re-arm it alone. Unlike the pre-bracket
    // design, the retained token survives the handoff: the present bracket's markers are
    // emitted around the actual queue present (PRESENT_START before, PRESENT_END after),
    // so the token must stay alive until present_end consumes it.
    g_state.srTagFrameIndexRecorded = false;
    g_state.lastSrTagFrameIndex = 0;
    g_state.fgTagFrameIndexRecorded = false;
    g_state.lastFgTagFrameIndex = 0;
    g_state.presentTokenArmed = true;
    return kSuccess;
}

int32_t present_start() noexcept {
    if (!sl_session_ready()) return kNotInitialized;
    // An unarmed present - an SR-only or skipped frame, or any present of a session that
    // never handed off - has no bracket to open: the marker is a no-op success rather than
    // a refusal, because the present seam fires on every present and a refusal would latch
    // the session on a frame that simply did not compose. An already-open bracket (a
    // present that threw between START and END) is the same no-op: its START already
    // reached the plugin, and a second START for the same frame would corrupt the
    // correlation. The START emits only under a bracket a successful handoff armed.
    if (!g_state.presentTokenArmed || g_state.presentStartEmitted ||
        g_state.frameToken == nullptr) {
        return kSuccess;
    }
    const sl::FrameToken* token = g_state.frameToken;
    const sl::Result result = slPCLSetMarker(sl::PCLMarker::ePresentStart, *token);
    if (result != sl::Result::eOk) return static_cast<int32_t>(result);
    g_state.presentStartEmitted = true;
    g_state.presentMarkers.record(kPresentMarkerStart, token);
    return kSuccess;
}

int32_t present_end() noexcept {
    if (!sl_session_ready()) return kNotInitialized;
    // An unarmed present has no bracket to close: same no-op success as the START.
    if (!g_state.presentTokenArmed) {
        return kSuccess;
    }
    // The END marker closes only a bracket a START actually opened: without a successful
    // START (its marker call failed, or the END arrived without one) there is no open
    // bracket to close, and the log must never read an END without its START. Either way
    // the bracket is the composed frame's terminal act: an armed bracket whose START never
    // emitted is consumed here exactly like a successful one, so its failure cannot leave
    // a stale bracket for a later present to open. What the END consumes is the retained
    // token the whole frame recorded under, so the next frame's tags obtain a fresh token
    // under a fresh index.
    sl::Result result = sl::Result::eOk;
    if (g_state.presentStartEmitted && g_state.frameToken != nullptr) {
        const sl::FrameToken* token = g_state.frameToken;
        result = slPCLSetMarker(sl::PCLMarker::ePresentEnd, *token);
        if (result == sl::Result::eOk) g_state.presentMarkers.record(kPresentMarkerEnd, token);
    }
    g_state.presentStartEmitted = false;
    g_state.presentTokenArmed = false;
    g_state.srTagFrameIndexRecorded = false;
    g_state.fgTagFrameIndexRecorded = false;
    g_state.frameToken = nullptr;
    return result == sl::Result::eOk ? kSuccess : static_cast<int32_t>(result);
}

int32_t query_present_markers(uint32_t* startCount, uint32_t* endCount, uint32_t* eventCount,
                              uint32_t* events, const uint32_t eventsCapacity) noexcept {
    if (startCount == nullptr || endCount == nullptr) {
        return kInvalidParameter;
    }
    // The log counts by type; this oracle names its two types separately, so the read fills a
    // local count array and the two out-pointers take their entries from it.
    uint32_t counts[kMarkerTypeCapacity] = {};
    const int32_t result = g_state.presentMarkers.read(counts, kPresentMarkerTypeCount,
                                                      eventCount, events, eventsCapacity);
    if (result != kSuccess) {
        return result;
    }
    *startCount = counts[kPresentMarkerStart];
    *endCount = counts[kPresentMarkerEnd];
    return kSuccess;
}

// Emits one PCL marker under the retained frame token and records it in the reflex-marker
// log. Shared by the simulation and render-submit entries: all four require the ready
// session and a retained token, because a marker emitted under no frame index would break
// the same-token correlation the whole surface exists for.
static int32_t emit_reflex_marker(const ReflexMarkerType type, const sl::PCLMarker marker) noexcept {
    if (!sl_session_ready()) {
        return kNotInitialized;
    }
    // A frame that never ran its input sample has no token: the input seam is what obtains
    // the frame's token, and a marker emitted without one cannot correlate with the frame.
    if (g_state.frameToken == nullptr) {
        return kNotInitialized;
    }
    const sl::FrameToken* token = g_state.frameToken;
    const sl::Result result = slPCLSetMarker(marker, *token);
    if (result != sl::Result::eOk) {
        return static_cast<int32_t>(result);
    }
    g_state.reflexMarkers.record(type, token);
    return kSuccess;
}

int32_t install_pcl_window(const uint64_t hwnd) noexcept {
    if (hwnd == 0) return kInvalidParameter;
    const HWND window = reinterpret_cast<HWND>(static_cast<std::uintptr_t>(hwnd));
    if (g_pclWindow == window) {
        refresh_pcl_stats_message();
        return kSuccess;
    }
    if (g_pclWindow != nullptr) return kInvalidParameter;

    SetLastError(0);
    const LONG_PTR previous = SetWindowLongPtrW(
        window, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(&pcl_window_proc));
    if (previous == 0 && GetLastError() != 0) return kFailure;

    g_previousWindowProc = reinterpret_cast<WNDPROC>(previous);
    g_pclWindow = window;
    refresh_pcl_stats_message();
    return kSuccess;
}

int32_t reflex_input_sample() noexcept {
    if (!sl_session_ready()) {
        return kNotInitialized;
    }
    // The frame-start seam obtains the frame's token unconditionally: a retained token at
    // input-sample time belongs to a previous frame that never reached its present end (an
    // exception between the seams), and reusing it would emit this frame's markers under a
    // stale index. Replacing it advances the frame exactly like the guide's obtain-at-frame-
    // start pattern, and the tag calls below reuse the retained token rather than advancing
    // it again, so one frame still records everything under one index.
    sl::Result result = slGetNewFrameToken(g_state.frameToken);
    if (result != sl::Result::eOk) {
        return static_cast<int32_t>(result);
    }
    // Reflex sleep is mandatory every frame even with low-latency mode off, and frame start
    // is where the app should sleep: the tag calls used to run the sleep when they obtained
    // the token, and with the input seam obtaining it first, this is the call that keeps the
    // sleep in the production path. The tag calls keep their own obtain-and-sleep for
    // callers that never run an input sample, and never sleep twice on one frame because
    // they only sleep when they themselves obtain the token.
    result = slReflexSleep(*g_state.frameToken);
    if (result != sl::Result::eOk) {
        invalidate_frame_eligibility();
        return static_cast<int32_t>(result);
    }
    // PCL sends a private Win32 message periodically. The window procedure records it and
    // this first post-poll seam associates it with the frame that consumes that input.
    if (!g_pclPingPending.exchange(false, std::memory_order_acq_rel)) return kSuccess;
    result = slPCLSetMarker(sl::PCLMarker::ePCLatencyPing, *g_state.frameToken);
    if (result != sl::Result::eOk) return static_cast<int32_t>(result);
    g_state.reflexMarkers.record(kReflexMarkerInputSample, g_state.frameToken);
    return kSuccess;
}

int32_t reflex_marker(const uint32_t markerType) noexcept {
    // The one place a ReflexMarkerType becomes an sl::PCLMarker. INPUT_SAMPLE is deliberately
    // absent: its seam obtains the frame's token and runs the Reflex sleep before it emits
    // anything, so it is its own entry rather than a marker this one could stand in for, and
    // naming it here would emit a ping without the frame start that earns it.
    switch (markerType) {
        case kReflexMarkerSimulationStart:
            return emit_reflex_marker(kReflexMarkerSimulationStart,
                                      sl::PCLMarker::eSimulationStart);
        case kReflexMarkerSimulationEnd:
            return emit_reflex_marker(kReflexMarkerSimulationEnd, sl::PCLMarker::eSimulationEnd);
        case kReflexMarkerRenderSubmitStart:
            return emit_reflex_marker(kReflexMarkerRenderSubmitStart,
                                      sl::PCLMarker::eRenderSubmitStart);
        case kReflexMarkerRenderSubmitEnd:
            return emit_reflex_marker(kReflexMarkerRenderSubmitEnd,
                                      sl::PCLMarker::eRenderSubmitEnd);
        default:
            return kInvalidParameter;
    }
}

int32_t query_reflex_markers(uint32_t* typeCounts, uint32_t* eventCount, uint32_t* events,
                             const uint32_t eventsCapacity) noexcept {
    return g_state.reflexMarkers.read(typeCounts, kReflexMarkerTypeCount, eventCount, events,
                                      eventsCapacity);
}

// The Reflex options record, shared by the READY registration and the frame-limit record:
// eLowLatency is the mode the contract pins even without a Reflex UI, the cap is the stored
// frameLimitUs (zero is no Reflex-side cap), and the marker/thread fields stay the SDK
// defaults.
sl::ReflexOptions make_reflex_options() noexcept {
    sl::ReflexOptions options{};
    options.mode = sl::ReflexMode::eLowLatency;
    options.frameLimitUs = g_state.reflexFrameLimitUs;
    return options;
}

int32_t record_reflex_options() noexcept {
    if (!sl_session_ready()) {
        return kNotInitialized;
    }
    // The one registration the pinned Reflex guide requires: called once at the READY
    // transition and never per frame, because the guide says there is no need to repeat the
    // call while the options do not change.
    // The call count advances with the attempt itself, so the oracle proves the call
    // happened exactly once whether or not the plugin accepted it.
    g_state.reflexSetOptionsCalls += 1;
    const sl::Result result = slReflexSetOptions(make_reflex_options());
    if (result != sl::Result::eOk) {
        return static_cast<int32_t>(result);
    }
    g_state.reflexOptionsRecorded = true;
    g_state.reflexMode = static_cast<uint32_t>(sl::ReflexMode::eLowLatency);
    return kSuccess;
}

int32_t record_reflex_frame_limit(const uint32_t frameLimitUs) noexcept {
    if (!sl_session_ready()) {
        return kNotInitialized;
    }
    // The registration has to exist before its cap can change: a session whose READY
    // registration never succeeded has no Reflex options for the cap to join, and recording
    // one here would make the exactly-once-at-READY discipline unprovable.
    if (!g_state.reflexOptionsRecorded) {
        return kInvalidParameter;
    }
    // A cap that is already in effect records nothing. The guide is explicit that the call
    // need not repeat while the options do not change, and the host calls this from the frame
    // seam that reads Minecraft's limit, which answers the same value on almost every frame.
    if (frameLimitUs == g_state.reflexFrameLimitUs) {
        return kSuccess;
    }
    // Stored before the record so make_reflex_options reads it, restored on failure, so a
    // refused record leaves the plugin's options and the stored cap exactly as they were.
    const uint32_t previous = g_state.reflexFrameLimitUs;
    g_state.reflexFrameLimitUs = frameLimitUs;
    g_state.reflexSetOptionsCalls += 1;
    const sl::Result result = slReflexSetOptions(make_reflex_options());
    if (result != sl::Result::eOk) {
        g_state.reflexFrameLimitUs = previous;
        return static_cast<int32_t>(result);
    }
    return kSuccess;
}

int32_t query_reflex_options(uint32_t* mode, uint32_t* setOptionsCalls) noexcept {
    if (!sl_session_ready()) {
        return kNotInitialized;
    }
    // No record means no registration to report, the same gate the FG options oracle uses:
    // a session whose options never recorded has nothing to answer with.
    if (!g_state.reflexOptionsRecorded) {
        return kInvalidParameter;
    }
    if (mode == nullptr || setOptionsCalls == nullptr) {
        return kInvalidParameter;
    }
    *mode = g_state.reflexMode;
    *setOptionsCalls = g_state.reflexSetOptionsCalls;
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
    const sl::Result result = slDLSSGGetState(sl::ViewportHandle{kFgViewportId}, state, nullptr);
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
    const sl::Result result = slDLSSGGetState(sl::ViewportHandle{kFgViewportId}, state, nullptr);
    if (result != sl::Result::eOk) {
        return static_cast<int32_t>(result);
    }
    // The status is reported as the raw word: eDLSSGStatusOk is zero and every failure bit
    // is its own mask, so the snapshot carries the word for the caller to compare against
    // the enum's bit vocabulary rather than a boolean that would hide which bit is set.
    *status = static_cast<uint32_t>(state.status);
    // Streamline's documented FPS calculation multiplies app FPS by this value: two means
    // one real plus one generated presentation per app frame.
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

// Records one y-inverting blit: source rows 0..h land at destination rows h..0, exactly
// Minecraft's own present blit, so the owned destination holds the backbuffer's orientation
// of the engine's image while the engine's image itself stays put. `isDepth` names the
// aspect, which also names the filter: NEAREST is mandatory for depth aspects, LINEAR
// matches Minecraft's present blit for the colours. Both images are handed back in the
// layouts they arrived in - the engine's GENERAL resting layout, and the owned copy's
// GENERAL, which is what the FG tag declares it in.
static void record_flip_blit(const VkCommandBuffer commandBuffer, const uint64_t sourceImage,
                             DlssOwnedImage& destination, const uint32_t width,
                             const uint32_t height, const bool isDepth) noexcept {
    const VkImageSubresourceRange range = image_range_of(isDepth);
    record_layout_transition(commandBuffer, from_uint64<VkImage>(sourceImage), range,
                             kEngineRestingLayout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
    record_layout_transition(commandBuffer, destination.image, range, destination.layout,
                             VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
    destination.layout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;

    VkImageBlit region{};
    region.srcSubresource = VkImageSubresourceLayers{range.aspectMask, 0, 0, 1};
    region.srcOffsets[0] = VkOffset3D{0, 0, 0};
    region.srcOffsets[1] =
        VkOffset3D{static_cast<int32_t>(width), static_cast<int32_t>(height), 1};
    region.dstSubresource = VkImageSubresourceLayers{range.aspectMask, 0, 0, 1};
    // The destination's y corners are inverted: source row 0 lands at destination row h.
    region.dstOffsets[0] = VkOffset3D{0, static_cast<int32_t>(height), 0};
    region.dstOffsets[1] = VkOffset3D{static_cast<int32_t>(width), 0, 1};
    vkCmdBlitImage(commandBuffer, from_uint64<VkImage>(sourceImage),
                   VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, destination.image,
                   VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region,
                   isDepth ? VK_FILTER_NEAREST : VK_FILTER_LINEAR);

    record_layout_transition(commandBuffer, from_uint64<VkImage>(sourceImage), range,
                             VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, kEngineRestingLayout);
    record_layout_transition(commandBuffer, destination.image, range,
                             VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, kEngineRestingLayout);
    destination.layout = kEngineRestingLayout;
}

// Records the frame's DLSS SR evaluation on the caller's command buffer, consuming the frame
// token mc_dlss_tag_sr_resources obtained and retained for this frame. The plugin needs the
// per-frame constants or its begin event fails (sl.dlss returns eErrorMissingConstants), and
// the frame token chained through slSetConstants and slEvaluateFeature must be the one the
// frame's resources were tagged with, so both record against the retained token.
// The module's motion image is the one evaluation input the caller's restore does not cover:
// mc_dlss_evaluate returns the engine's colour and depth to where Minecraft expects them but
// leaves the motion image in the read state. The restore returns it to the engine-resting
// layout the motion pass and the SR path expect it in between frames, and runs whether or
// not the evaluation succeeded, matching the caller's own restore discipline for the
// engine's images.
static void restore_motion_to_engine_resting_layout(const VkCommandBuffer commandBuffer) noexcept {
    const uint64_t motionImage = to_uint64(g_state.motionImage.image);
    record_layout_transition(commandBuffer, g_state.motionImage.image, image_range_of(false),
                             current_layout_of(motionImage), kEngineRestingLayout);
    note_layout_after_transition(motionImage, kEngineRestingLayout);
}

// Copies a 16-float row-major matrix (the layout sl::float4x4 stores and the ABI carries)
// into one of the sl::Constants matrix fields.
static void write_matrix(sl::float4x4& dest, const float* rowMajor) noexcept {
    for (uint32_t r = 0; r < 4; ++r) {
        sl::float4& row = dest[r];
        row.x = rowMajor[r * 4 + 0];
        row.y = rowMajor[r * 4 + 1];
        row.z = rowMajor[r * 4 + 2];
        row.w = rowMajor[r * 4 + 3];
    }
}

// The y flip the FG viewport's constants carry, per matrix role. The ABI's matrices are
// row-vector (v' = v * M), so where a matrix maps from and to decides the flip: F =
// diag(1, -1, 1, 1) applied to the output side is M' = M * F (column 1 negated - flat
// indices 1, 5, 9, 13), to the input side M' = F * M (row 1 negated - flat indices 4, 5,
// 6, 7), and to both sides the conjugation M' = F * M * F (row 1 and column 1 negated, the
// [1][1] element twice - unchanged). The FG viewport's tags name y-flipped backbuffer
// copies, so its viewToClip maps a camera-space row vector into the flipped clip space
// (output-side flip), its clipToView maps a flipped clip-space row vector back into camera
// space (input-side flip), and the reprojection pair maps flipped clip to flipped clip
// (conjugation). The asymmetry is what keeps the FG record's clipToView the exact
// row-vector inverse of its viewToClip: (M * F) * (F * M^-1) = M * M^-1 = I. The camera's
// world-space position and basis and the frustum scalars are orientation-free and carry
// unchanged.
static void flip_matrix_y_in_place(float* rowMajor, const bool negateRow1,
                                   const bool negateCol1) noexcept {
    for (uint32_t r = 0; r < 4; ++r) {
        for (uint32_t c = 0; c < 4; ++c) {
            // Negate where exactly one side of the flip applies. The [1][1] element
            // satisfies both conditions when the conjugation is applied, so the XOR leaves
            // it unchanged - the double flip the conjugation's two sides give it.
            if ((negateRow1 && r == 1) != (negateCol1 && c == 1)) {
                rowMajor[r * 4 + c] = -rowMajor[r * 4 + c];
            }
        }
    }
}

// Writes a row-major matrix into an sl::float4x4 with the y flip applied, for the FG
// viewport's constants record. negateRow1/negateCol1 name the flip's sides exactly as
// flip_matrix_y_in_place does: the viewToClip records the output-side flip (column 1), the
// clipToView the input-side flip (row 1), and the clip-to-clip pair the conjugation.
static void write_matrix_flipped_y(sl::float4x4& dest, const float* rowMajor,
                                   const bool negateRow1, const bool negateCol1) noexcept {
    write_matrix(dest, rowMajor);
    if (negateRow1) {
        dest[1].x = -dest[1].x;
        dest[1].y = -dest[1].y;
        dest[1].z = -dest[1].z;
        dest[1].w = -dest[1].w;
    }
    if (negateCol1) {
        dest[0].y = -dest[0].y;
        dest[1].y = -dest[1].y;
        dest[2].y = -dest[2].y;
        dest[3].y = -dest[3].y;
    }
}

int32_t query_camera_constants(McDlssCameraConstants* out) noexcept {
    if (out == nullptr) {
        return kInvalidParameter;
    }
    // The oracle answers only once an evaluation actually recorded constants: before that
    // there is no record any caller could compare against, and the refusal is exactly what
    // makes the pre-evaluation state observable to the test. reset_state clears the record
    // with the rest of the struct, so a fresh fork answers the same refusal.
    if (!g_state.cameraConstantsRecorded) {
        return kNotInitialized;
    }
    *out = g_state.lastCameraConstants;
    return kSuccess;
}

int32_t query_fg_camera_constants(McDlssCameraConstants* out) noexcept {
    if (out == nullptr) {
        return kInvalidParameter;
    }
    // The FG oracle answers only once a composed frame's FG-side slSetConstants actually
    // recorded constants: before that there is no FG-side record any caller could compare
    // against, and the refusal is exactly what makes the FG side's independence observable -
    // an SR-only evaluation establishes the SR record and never this one, and only a
    // composed frame's FG-viewport record does. reset_state clears the record with the rest
    // of the struct, so a fresh fork answers the same refusal.
    if (!g_state.fgCameraConstantsRecorded) {
        return kNotInitialized;
    }
    *out = g_state.lastFgCameraConstants;
    return kSuccess;
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
    // The frame's real camera, captured at the world projection seam: the unjittered
    // view-to-clip projection the world rendered with and its inverse, the jitter-free
    // clip-to-prev-clip pair, the frustum scalars, and the camera's world-space position and
    // orthonormal right/up/forward basis. Together with the flags above these are every
    // non-optional field of sl::Constants - "all parameters must be provided unless they are
    // marked as optional" - and an unwritten field is not a defaulted one: sl::float4x4 and
    // the scalars default-construct to INVALID_FLOAT (3.4e38). DLSS SR survives missing
    // reprojection matrices because cameraMotionIncluded routes it to the motion field; the
    // DLSS-G plugin reads clipToPrevClip directly, and FLT_MAX there is the upside-down world
    // ghost that appeared on generated frames only while the rendered frames stayed correct.
    //
    // The plugin interpolates the generated frame's camera from these, and its auto
    // scene-change detection verifies the basis is orthonormal before it runs - all-zero
    // matrices fail that check and leave the plugin without a camera to interpolate across,
    // which is the fence-stuck symptom the human probe traced to zero constants. The matrices
    // are row-major on both sides of the ABI, so the floats copy straight into sl::float4x4.
    write_matrix(constants.cameraViewToClip, info.camera.view_to_clip);
    write_matrix(constants.clipToCameraView, info.camera.clip_to_view);
    write_matrix(constants.clipToPrevClip, info.camera.clip_to_prev_clip);
    write_matrix(constants.prevClipToClip, info.camera.prev_clip_to_clip);
    constants.cameraNear = info.camera.near_plane;
    constants.cameraFar = info.camera.far_plane;
    constants.cameraFOV = info.camera.fov_radians;
    constants.cameraAspectRatio = info.camera.aspect_ratio;
    constants.cameraPos = sl::float3(info.camera.pos[0], info.camera.pos[1], info.camera.pos[2]);
    constants.cameraUp = sl::float3(info.camera.up[0], info.camera.up[1], info.camera.up[2]);
    constants.cameraRight =
        sl::float3(info.camera.right[0], info.camera.right[1], info.camera.right[2]);
    constants.cameraFwd = sl::float3(info.camera.fwd[0], info.camera.fwd[1], info.camera.fwd[2]);

    sl::Result result = slSetConstants(constants, *frameToken, sl::ViewportHandle{kSrViewportId});
    if (result != sl::Result::eOk) {
        // A failed frame has no history the next one could reuse, so the SR-only frame
        // consumes its token here. The composed frame keeps it instead: its FG tag
        // re-declares the shared inputs in the engine-resting layout after the evaluation,
        // and the present handoff consumes the token when it accepts the frame.
        if (!g_state.fgTagFrameIndexRecorded) {
            g_state.frameToken = nullptr;
        }
        // The evaluation never recorded, but the caller's transitions above still moved the
        // motion image into the read state before this call; the image goes back to the
        // engine-resting layout the motion pass and the SR path expect it in, rather than
        // being left for the next frame to find in the wrong state.
        restore_motion_to_engine_resting_layout(commandBuffer);
        return static_cast<int32_t>(result);
    }
    // The constants reached the plugin: the oracle records exactly what this call carried,
    // so a later query proves the caller's camera arrived unchanged - the jitter the SR
    // viewport received included, raw. Recorded only after slSetConstants answered eOk - a
    // failed call claims no constants.
    g_state.cameraConstantsRecorded = true;
    g_state.lastCameraConstants = info.camera;
    g_state.lastCameraConstants.jitter = info.jitter;

    // The composed frame's FG side needs its own constants record on the FG viewport: the
    // DLSS-G plugin reads per-frame constants from the viewport its options, state, and
    // tags were recorded on, and after the viewport split the SR viewport's record no
    // longer reaches it. The record carries the same retained frame token - the token is
    // shared, never replaced or consumed here - so both viewports' records stay on the one
    // frame index the tags obtained.
    //
    // The FG record also carries the FG viewport's orientation: its tags name the
    // backbuffer-oriented copies - the engine's images mirrored about the horizontal axis,
    // the motion field flipped with its y component negated - so the constants describing
    // those images carry the matching flip, one per matrix role. The matrices are
    // row-vector (v' = v * M), so the viewToClip carries the output-side flip M * F, the
    // clipToView the input-side flip F * M^-1 (which keeps the pair exact inverses), and
    // the reprojection pair the conjugation F * M * F; the pixel-space jitter's y negates
    // and the camera's world-space position and basis and the frustum scalars are
    // orientation-free and carry unchanged.
    // The SR record above stays raw: a flip on one viewport without the matching images -
    // or the reverse - is the half-fix that desynchronizes the constants from the tags, and
    // the viewport split is exactly what lets each side carry its own.
    //
    // Recorded only for a frame whose FG tag recorded: an SR-only frame has no FG side to
    // give constants to. A failed FG record aborts the evaluation before it runs, exactly
    // like a failed SR record, and the FG oracle stays unrecorded - the plugin received no
    // FG constants.
    if (g_state.fgTagFrameIndexRecorded) {
        sl::Constants fgConstants = constants;
        fgConstants.jitterOffset = sl::float2(info.jitter.x, -info.jitter.y);
        write_matrix_flipped_y(fgConstants.cameraViewToClip, info.camera.view_to_clip,
                               false, true);
        write_matrix_flipped_y(fgConstants.clipToCameraView, info.camera.clip_to_view,
                               true, false);
        write_matrix_flipped_y(fgConstants.clipToPrevClip, info.camera.clip_to_prev_clip,
                               true, true);
        write_matrix_flipped_y(fgConstants.prevClipToClip, info.camera.prev_clip_to_clip,
                               true, true);
        result = slSetConstants(fgConstants, *frameToken, sl::ViewportHandle{kFgViewportId});
        if (result != sl::Result::eOk) {
            restore_motion_to_engine_resting_layout(commandBuffer);
            return static_cast<int32_t>(result);
        }
        g_state.fgCameraConstantsRecorded = true;
        // The FG oracle reports exactly what the FG viewport received: the per-role
        // flipped matrices and the negated jitter, so a query proves the flip reached the
        // record and never the SR one.
        g_state.lastFgCameraConstants = info.camera;
        flip_matrix_y_in_place(g_state.lastFgCameraConstants.view_to_clip, false, true);
        flip_matrix_y_in_place(g_state.lastFgCameraConstants.clip_to_view, true, false);
        flip_matrix_y_in_place(g_state.lastFgCameraConstants.clip_to_prev_clip, true, true);
        flip_matrix_y_in_place(g_state.lastFgCameraConstants.prev_clip_to_clip, true, true);
        g_state.lastFgCameraConstants.jitter.x = info.jitter.x;
        g_state.lastFgCameraConstants.jitter.y = -info.jitter.y;
    }

    // The viewport handle is chained into the evaluate inputs: the common plugin reads the
    // viewport id from there and refuses an evaluate that does not chain it. The SR
    // evaluation records against the SR viewport; DLSS-G has no per-frame evaluate of its
    // own - it reads its viewport's tags and constants at present time.
    sl::ViewportHandle viewport{kSrViewportId};
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
    // The motion image returns to the engine-resting layout it lives in between frames, on
    // the success path exactly as on the constants-failure path above.
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
        // Reflex sleep is mandatory every frame even with low-latency mode off.
        result = slReflexSleep(*g_state.frameToken);
        if (result != sl::Result::eOk) {
            invalidate_frame_eligibility();
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
    result = slSetTagForFrame(*frameToken, sl::ViewportHandle{kSrViewportId}, tags, numTags, commandBuffer);
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
        // Reflex sleep is mandatory every frame even with low-latency mode off.
        result = slReflexSleep(*g_state.frameToken);
        if (result != sl::Result::eOk) {
            invalidate_frame_eligibility();
            return static_cast<int32_t>(result);
        }
    }
    sl::FrameToken* frameToken = g_state.frameToken;

    const uint32_t renderWidth = g_state.renderWidth;
    const uint32_t renderHeight = g_state.renderHeight;
    const uint32_t outputWidth = g_state.outputWidth;
    const uint32_t outputHeight = g_state.outputHeight;

    // The frame's post-evaluation FG tag call records the orientation copies: three y-inverting
    // blits from the engine's depth, HUD-less, and UI images into the module's flipped copies -
    // the motion copy is filled by the motion dispatches on the same command buffer before
    // this call. The copies are what the tag below declares, in the backbuffer's orientation
    // rather than the engine's. The engine images are read as transfer sources and handed
    // back in their engine-resting layout; nothing here writes them.
    //
    // The frame's *first* FG tag call - the one before the SR tag, which exists to obtain the
    // frame token and to mark the frame composed for the evaluation - skips the blits, and
    // that is the whole point of recording them here instead. The blit is a snapshot the
    // DLSS-G plugin reads at present, so its timing decides what the plugin interpolates: run
    // on the first call it would capture the engine's colour target before mc_dlss_present_output
    // copied this frame's upscaled output into it, which is the previous frame's finished image
    // with its whole HUD composited in - a HUD-less tag that is neither this frame's nor
    // HUD-less. Run here, after the output copy, the target holds this frame's world alone.
    // The blits are recorded once per frame either way; a third call within one frame would
    // skip them, as the second used to.
    //
    // The two calls are told apart by the SR side of this frame's record: the SR tag sits
    // between them, so its index matches the retained token only from the post-evaluation call
    // onward. The token index itself advances between frames and never within one, so the
    // fgCopiedFrameIndex guard still holds the blits to once per frame.
    const uint32_t frameIndex = static_cast<uint32_t>(*frameToken);
    const bool afterSrTag =
        g_state.srTagFrameIndexRecorded && g_state.lastSrTagFrameIndex == frameIndex;
    if (afterSrTag && (!g_state.fgCopiesRecorded || g_state.fgCopiedFrameIndex != frameIndex)) {
        record_flip_blit(commandBuffer, info.depth.image, g_state.fgDepthImage,
                         renderWidth, renderHeight, true);
        record_flip_blit(commandBuffer, info.hudless.image, g_state.fgHudlessImage,
                         outputWidth, outputHeight, false);
        record_flip_blit(commandBuffer, info.ui.image, g_state.fgUiImage,
                         outputWidth, outputHeight, false);
        g_state.fgCopiesRecorded = true;
        g_state.fgCopiedFrameIndex = frameIndex;
    }

    // The declared state is the layout the copies rest in when the frame is tagged: the
    // blits above leave the depth, HUD-less, and UI copies in the engine-resting GENERAL
    // layout, the motion dispatches leave the flipped motion copy there too, and nothing
    // in this call moves any of them again. SL reads the tagged resources at present time,
    // and the guide requires the declared state to be the layout they are in then. The
    // copies are module-owned and no evaluation transition ever reaches them, so the first
    // call's declarations stay accurate through present - the second call's re-declaration
    // is the same record under the same retained token, keeping the composed frame's
    // post-evaluation tag discipline without touching the engine's images.
    sl::Resource depthResource = make_tagged_resource(
        reinterpret_cast<void*>(g_state.fgDepthImage.image),
        reinterpret_cast<void*>(g_state.fgDepthImage.memory),
        reinterpret_cast<void*>(g_state.fgDepthImage.view),
        kEngineRestingLayout, renderWidth, renderHeight, info.depth.format);
    sl::Resource hudlessResource = make_tagged_resource(
        reinterpret_cast<void*>(g_state.fgHudlessImage.image),
        reinterpret_cast<void*>(g_state.fgHudlessImage.memory),
        reinterpret_cast<void*>(g_state.fgHudlessImage.view),
        kEngineRestingLayout, outputWidth, outputHeight, info.hudless.format);
    sl::Resource uiResource = make_tagged_resource(
        reinterpret_cast<void*>(g_state.fgUiImage.image),
        reinterpret_cast<void*>(g_state.fgUiImage.memory),
        reinterpret_cast<void*>(g_state.fgUiImage.view),
        kEngineRestingLayout, outputWidth, outputHeight, info.ui.format);
    sl::Resource motionResource = make_tagged_resource(
        reinterpret_cast<void*>(g_state.fgMotionImage.image),
        reinterpret_cast<void*>(g_state.fgMotionImage.memory),
        reinterpret_cast<void*>(g_state.fgMotionImage.view),
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
    // The motion source is the module's own flipped motion copy on every route, filled with
    // the backbuffer-oriented mirror of the NDC motion payload the DLSS-G evaluation expects;
    // the pre-checks above already refused the call until that image exists at the
    // configured size, so all four tags always record together.
    tags[3] = sl::ResourceTag(&motionResource, sl::kBufferTypeMotionVectors,
                              sl::ResourceLifecycle::eValidUntilPresent, &renderExtent);

    // The command buffer is the caller's shared recording: slSetTagForFrame takes it as an
    // opaque pointer and this module only ever records on it, never submits.
    // As above, pass the VkCommandBuffer handle, not the address of the local handle variable.
    result = slSetTagForFrame(*frameToken, sl::ViewportHandle{kFgViewportId}, tags, 4, commandBuffer);
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
