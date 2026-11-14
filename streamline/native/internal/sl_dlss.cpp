#include "internal/sl_dlss.h"

#include "internal/common.h"
#include "internal/ngx.h"
#include "internal/state.h"
#include "internal/timing.h"

#include <sl.h>
#include <sl_core_api.h>
#include <sl_dlss.h>
#include <sl_dlss_d.h>
#include <sl_dlss_g.h>
#include <sl_pcl.h>
#include <sl_reflex.h>

#include <atomic>
#include <windows.h>

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
        const uint32_t statsMessage = state.statsWindowMessage;
        if (g_pclWindow != nullptr && statsMessage != 0) {
            ChangeWindowMessageFilterEx(g_pclWindow, statsMessage, MSGFLT_ALLOW, nullptr);
        }
    }
}

void configure_pcl_stats() noexcept {
    sl::PCLOptions options{};
    options.virtualKey = sl::PCLHotKey::eUsePingMessage;
    slPCLSetOptions(options);
    refresh_pcl_stats_message();
}

}

bool sl_session_ready() noexcept {
    return g_state.streamlineInitialized && g_state.proxyDevice != 0;
}

void shutdown_streamline() noexcept {
    if (g_state.streamlineInitialized) {
        slShutdown();
    }
}

int32_t is_feature_supported_sl(const uint32_t feature, const uint64_t vkPhysicalDevice) noexcept {
    if (!g_state.streamlineInitialized) {
        return kNotInitialized;
    }
    void* physDev = from_uint64<void*>(vkPhysicalDevice);
    if (physDev == nullptr && g_state.proxyPhysicalDevice != 0) {
        physDev = from_uint64<void*>(g_state.proxyPhysicalDevice);
    }
    if (physDev == nullptr && g_state.physicalDeviceValue != 0) {
        physDev = from_uint64<void*>(g_state.physicalDeviceValue);
    }
    if (physDev == nullptr) {
        return kInvalidParameter;
    }
    sl::AdapterInfo adapterInfo{};
    adapterInfo.vkPhysicalDevice = physDev;
    const sl::Result result = slIsFeatureSupported(static_cast<sl::Feature>(feature), adapterInfo);
    return result == sl::Result::eOk ? kSuccess : static_cast<int32_t>(result);
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

    if (static_cast<NVSDK_NGX_PerfQuality_Value>(qualityMode) ==
        NVSDK_NGX_PerfQuality_Value_DLAA) {
        *renderWidth = outputWidth;
        *renderHeight = outputHeight;
        return kSuccess;
    }

    sl::DLSSOptions options{};
    options.mode = static_cast<sl::DLSSMode>(qualityMode + 1);
    options.outputWidth = outputWidth;
    options.outputHeight = outputHeight;
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

int32_t query_optimal_dimensions_rr_sl(const uint32_t outputWidth, const uint32_t outputHeight,
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

    if (static_cast<NVSDK_NGX_PerfQuality_Value>(qualityMode) ==
        NVSDK_NGX_PerfQuality_Value_DLAA) {
        *renderWidth = outputWidth;
        *renderHeight = outputHeight;
        return kSuccess;
    }

    sl::DLSSDOptions options{};
    options.mode = static_cast<sl::DLSSMode>(qualityMode + 1);
    options.outputWidth = outputWidth;
    options.outputHeight = outputHeight;
    options.colorBuffersHDR = sl::Boolean::eTrue;

    sl::DLSSDOptimalSettings settings{};
    const sl::Result result = slDLSSDGetOptimalSettings(options, settings);
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
        default:
            break;
    }

    const sl::Result result = slDLSSSetOptions(sl::ViewportHandle{kSrViewportId}, options);
    return result == sl::Result::eOk ? kSuccess : static_cast<int32_t>(result);
}

sl::DLSSGOptions make_fg_options(const uint32_t numBackBuffers,
                                  const sl::DLSSGMode mode) noexcept {
    constexpr auto kSingleQueueParallelism =
        sl::DLSSGQueueParallelismMode::eBlockPresentingClientQueue;
    sl::DLSSGOptions options{};
    options.mode = mode;
    options.numFramesToGenerate = g_state.fgOptions.numFramesToGenerate;
    options.flags = sl::DLSSGFlags::eRetainResourcesWhenOff | sl::DLSSGFlags::eEnableFullscreenMenuDetection;
    options.queueParallelismMode = kSingleQueueParallelism;
    options.enableUserInterfaceRecomposition = sl::Boolean::eTrue;
    options.numBackBuffers = numBackBuffers;
    options.mvecDepthWidth = g_state.renderWidth;
    options.mvecDepthHeight = g_state.renderHeight;
    options.colorWidth = g_state.outputWidth;
    options.colorHeight = g_state.outputHeight;
    options.colorBufferFormat = VK_FORMAT_R8G8B8A8_UNORM;
    options.mvecBufferFormat = static_cast<uint32_t>(kMotionFormat);
    options.depthBufferFormat = VK_FORMAT_D32_SFLOAT;
    options.hudLessBufferFormat = VK_FORMAT_R8G8B8A8_UNORM;
    options.uiBufferFormat = VK_FORMAT_R8G8B8A8_UNORM;
    return options;
}

sl::DLSSGOptions make_fg_options(const sl::DLSSGMode mode) noexcept {
    return make_fg_options(g_state.fgOptions.numBackBuffers, mode);
}

int32_t record_fg_options(const uint32_t numBackBuffers) noexcept {
    if (!sl_session_ready()) {
        return kNotInitialized;
    }
    if (!valid_dimensions(g_state.outputWidth, g_state.outputHeight, g_state.renderWidth,
                          g_state.renderHeight)) {
        return kInvalidParameter;
    }

    const sl::Result result = slDLSSGSetOptions(sl::ViewportHandle{kFgViewportId}, make_fg_options(numBackBuffers, sl::DLSSGMode::eOn));
    if (result != sl::Result::eOk) {
        return static_cast<int32_t>(result);
    }
    g_state.fgOptions.recorded = true;
    g_state.fgOptions.numBackBuffers = numBackBuffers;
    return kSuccess;
}

int32_t record_fg_mode(const uint32_t fgEnabled) noexcept {
    if (!sl_session_ready()) {
        return kNotInitialized;
    }
    if (!g_state.fgOptions.recorded || !valid_dimensions(g_state.outputWidth, g_state.outputHeight,
                                                        g_state.renderWidth, g_state.renderHeight)) {
        return kInvalidParameter;
    }

    const sl::DLSSGMode mode = fgEnabled != 0 ? sl::DLSSGMode::eOn : sl::DLSSGMode::eOff;
    const sl::Result result = slDLSSGSetOptions(sl::ViewportHandle{kFgViewportId},
                                                make_fg_options(mode));
    return result == sl::Result::eOk ? kSuccess : static_cast<int32_t>(result);
}

int32_t record_fg_multiplier(const uint32_t numFramesToGenerate) noexcept {
    if (!sl_session_ready()) {
        return kNotInitialized;
    }
    if (!g_state.fgOptions.recorded || !valid_dimensions(g_state.outputWidth, g_state.outputHeight,
                                                        g_state.renderWidth, g_state.renderHeight)) {
        return kInvalidParameter;
    }
    sl::DLSSGState state{};
    const sl::Result stateResult = slDLSSGGetState(sl::ViewportHandle{kFgViewportId}, state, nullptr);
    if (stateResult != sl::Result::eOk) {
        return static_cast<int32_t>(stateResult);
    }
    if (numFramesToGenerate < 1 || numFramesToGenerate > state.numFramesToGenerateMax) {
        return kInvalidParameter;
    }
    const uint32_t previous = g_state.fgOptions.numFramesToGenerate;
    g_state.fgOptions.numFramesToGenerate = numFramesToGenerate;
    const sl::Result result = slDLSSGSetOptions(
        sl::ViewportHandle{kFgViewportId}, make_fg_options(sl::DLSSGMode::eOn));
    if (result != sl::Result::eOk) {
        g_state.fgOptions.numFramesToGenerate = previous;
        return static_cast<int32_t>(result);
    }
    return kSuccess;
}

int32_t query_fg_multiplier(uint32_t* current, uint32_t* max) noexcept {
    if (!sl_session_ready()) {
        return kNotInitialized;
    }
    if (!g_state.fgOptions.recorded) {
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
    *current = g_state.fgOptions.numFramesToGenerate;
    *max = state.numFramesToGenerateMax;
    return kSuccess;
}

int32_t record_present_handoff() noexcept {
    if (!sl_session_ready()) return kNotInitialized;
    if (!g_state.fgOptions.recorded || !images_match_configuration()) return kInvalidParameter;
    if (!g_state.frameEligibility.tagSetComplete()) return kInvalidParameter;
    if (!g_state.frameEligibility.hasToken()) return kNotInitialized;
    const sl::Result result = slDLSSGSetOptions(
        sl::ViewportHandle{kFgViewportId}, make_fg_options(sl::DLSSGMode::eOn));
    if (result != sl::Result::eOk) return static_cast<int32_t>(result);
    g_state.frameEligibility.consumeForHandoff();
    return kSuccess;
}

int32_t present_start() noexcept {
    if (!sl_session_ready()) return kNotInitialized;
    if (!g_state.frameEligibility.presentStartPending()) {
        return kSuccess;
    }
    const sl::FrameToken* token = g_state.frameEligibility.token();
    const sl::Result result = slPCLSetMarker(sl::PCLMarker::ePresentStart, *token);
    if (result != sl::Result::eOk) return static_cast<int32_t>(result);
    g_state.frameEligibility.markPresentStartEmitted();
    g_state.presentMarkers.record(kPresentMarkerStart, token);
    return kSuccess;
}

int32_t present_end() noexcept {
    if (!sl_session_ready()) return kNotInitialized;
    if (!g_state.frameEligibility.hasToken() && !g_state.frameEligibility.presentArmed()) {
        return kSuccess;
    }
    sl::Result result = sl::Result::eOk;
    if (g_state.frameEligibility.presentStartEmitted() && g_state.frameEligibility.hasToken()) {
        const sl::FrameToken* token = g_state.frameEligibility.token();
        result = slPCLSetMarker(sl::PCLMarker::ePresentEnd, *token);
        if (result == sl::Result::eOk) g_state.presentMarkers.record(kPresentMarkerEnd, token);
    }
    g_state.frameEligibility.consumePresent();
    return result == sl::Result::eOk ? kSuccess : static_cast<int32_t>(result);
}

int32_t query_present_markers(uint32_t* startCount, uint32_t* endCount, uint32_t* eventCount,
                              uint32_t* events, const uint32_t eventsCapacity) noexcept {
    if (startCount == nullptr || endCount == nullptr) {
        return kInvalidParameter;
    }
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

static int32_t emit_reflex_marker(const ReflexMarkerType type, const sl::PCLMarker marker) noexcept {
    if (!sl_session_ready()) {
        return kNotInitialized;
    }
    if (!g_state.frameEligibility.hasToken()) {
        return kNotInitialized;
    }
    const sl::FrameToken* token = g_state.frameEligibility.token();
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
    sl::Result result = slGetNewFrameToken(g_state.frameEligibility.tokenSlot());
    if (result != sl::Result::eOk) {
        return static_cast<int32_t>(result);
    }
    result = slReflexSleep(*g_state.frameEligibility.token());
    if (result != sl::Result::eOk) {
        g_state.frameEligibility.invalidate();
        return static_cast<int32_t>(result);
    }
    if (g_pclStatsMessage.load(std::memory_order_relaxed) == 0) {
        refresh_pcl_stats_message();
    }
    if (!g_pclPingPending.exchange(false, std::memory_order_acq_rel)) return kSuccess;
    result = slPCLSetMarker(sl::PCLMarker::ePCLatencyPing, *g_state.frameEligibility.token());
    if (result != sl::Result::eOk) return static_cast<int32_t>(result);
    g_state.reflexMarkers.record(kReflexMarkerInputSample, g_state.frameEligibility.token());
    return kSuccess;
}

int32_t reflex_marker(const uint32_t markerType) noexcept {
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

int32_t record_reflex_options() noexcept {
    if (!sl_session_ready()) {
        return kNotInitialized;
    }
    g_state.reflexSetOptionsCalls += 1;
    const sl::Result result = slReflexSetOptions(make_reflex_options());
    if (result != sl::Result::eOk) {
        return static_cast<int32_t>(result);
    }
    g_state.reflexOptionsRecorded = true;
    configure_pcl_stats();
    return kSuccess;
}

int32_t record_reflex_frame_limit(const uint32_t frameLimitUs) noexcept {
    if (!sl_session_ready()) {
        return kNotInitialized;
    }
    if (!g_state.reflexOptionsRecorded) {
        return kInvalidParameter;
    }
    if (frameLimitUs == g_state.reflexFrameLimitUs) {
        return kSuccess;
    }
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

int32_t record_reflex_mode(const uint32_t mode) noexcept {
    if (!sl_session_ready()) {
        return kNotInitialized;
    }
    if (mode > 2) {
        return kInvalidParameter;
    }
    const uint32_t previous = g_state.reflexMode;
    g_state.reflexMode = mode;
    g_state.reflexSetOptionsCalls += 1;
    const sl::Result result = slReflexSetOptions(make_reflex_options());
    if (result != sl::Result::eOk) {
        g_state.reflexMode = previous;
        return static_cast<int32_t>(result);
    }
    g_state.reflexOptionsRecorded = true;
    return kSuccess;
}

int32_t query_reflex_state(uint32_t* lowLatencyAvailable, uint32_t* latencyReportAvailable) noexcept {
    if (!sl_session_ready()) {
        return kNotInitialized;
    }
    sl::ReflexState state{};
    const sl::Result result = slReflexGetState(state);
    if (result != sl::Result::eOk) {
        return static_cast<int32_t>(result);
    }
    if (lowLatencyAvailable != nullptr) {
        *lowLatencyAvailable = state.lowLatencyAvailable ? 1 : 0;
    }
    if (latencyReportAvailable != nullptr) {
        *latencyReportAvailable = state.latencyReportAvailable ? 1 : 0;
    }
    return kSuccess;
}

static int32_t wait_on_inputs_semaphore(const VkDevice device, const VkSemaphore semaphore,
                                        const uint64_t value) noexcept {
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
    if (vkDevice == 0 || semaphore == 0) {
        return kInvalidParameter;
    }
    return wait_on_inputs_semaphore(from_uint64<VkDevice>(vkDevice),
                                    from_uint64<VkSemaphore>(semaphore), value);
}

int32_t wait_fg_inputs_idle() noexcept {
    if (!sl_session_ready() || g_state.device == VK_NULL_HANDLE) {
        return kNotInitialized;
    }
    if (!g_state.fgOptions.recorded) {
        return kInvalidParameter;
    }

    sl::DLSSGState state{};
    const sl::Result result = slDLSSGGetState(sl::ViewportHandle{kFgViewportId}, state, nullptr);
    if (result != sl::Result::eOk) {
        return static_cast<int32_t>(result);
    }
    if (state.inputsProcessingCompletionFence == nullptr) {
        return kSuccess;
    }

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
    if (!g_state.fgOptions.recorded) {
        return kInvalidParameter;
    }
    if (status == nullptr || numFramesPresented == nullptr ||
        lastPresentInputsProcessingFenceValue == nullptr ||
        inputsProcessingCompletionFence == nullptr) {
        return kInvalidParameter;
    }

    sl::DLSSGState state{};
    const sl::Result result = slDLSSGGetState(sl::ViewportHandle{kFgViewportId}, state, nullptr);
    if (result != sl::Result::eOk) {
        return static_cast<int32_t>(result);
    }
    *status = static_cast<uint32_t>(state.status);
    *numFramesPresented = state.numFramesActuallyPresented;
    *lastPresentInputsProcessingFenceValue = state.lastPresentInputsProcessingCompletionFenceValue;
    *inputsProcessingCompletionFence = reinterpret_cast<uint64_t>(state.inputsProcessingCompletionFence);
    return kSuccess;
}

int32_t query_fg_vsync_support(uint32_t* vsyncSupported) noexcept {
    if (!sl_session_ready()) {
        return kNotInitialized;
    }
    if (vsyncSupported == nullptr) {
        return kInvalidParameter;
    }
    sl::DLSSGState state{};
    const sl::Result result = slDLSSGGetState(sl::ViewportHandle{kFgViewportId}, state, nullptr);
    if (result != sl::Result::eOk) {
        return static_cast<int32_t>(result);
    }
    *vsyncSupported = state.bIsVsyncSupportAvailable ? 1 : 0;
    return kSuccess;
}

sl::Resource make_tagged_resource(void* native, void* memory, void* view, uint32_t state,
                                  uint32_t width, uint32_t height, uint32_t format) noexcept {
    sl::Resource resource(sl::ResourceType::eTex2d, native, memory, view, state);
    resource.width = width;
    resource.height = height;
    resource.nativeFormat = format;
    resource.mipLevels = 1;
    resource.arrayLayers = 1;
    resource.usage = 0;
    resource.flags = 0;
    return resource;
}

static void record_flip_blit(const VkCommandBuffer commandBuffer, const uint64_t sourceImage,
                             DlssOwnedImage& destination, const uint32_t width,
                             const uint32_t height, const bool isDepth) noexcept {
    const VkImageSubresourceRange range = image_range_of(isDepth);
    const ImageTransitionDesc preBlit[2] = {
        {from_uint64<VkImage>(sourceImage), range, kEngineRestingLayout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
         VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
         VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT},
        {destination.image, range, destination.layout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
         VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
         VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_WRITE_BIT}
    };
    record_batched_transitions(commandBuffer, preBlit, 2);
    destination.layout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;

    VkImageBlit region{};
    region.srcSubresource = VkImageSubresourceLayers{range.aspectMask, 0, 0, 1};
    region.srcOffsets[0] = VkOffset3D{0, 0, 0};
    region.srcOffsets[1] =
        VkOffset3D{static_cast<int32_t>(width), static_cast<int32_t>(height), 1};
    region.dstSubresource = VkImageSubresourceLayers{range.aspectMask, 0, 0, 1};
    region.dstOffsets[0] = VkOffset3D{0, static_cast<int32_t>(height), 0};
    region.dstOffsets[1] = VkOffset3D{static_cast<int32_t>(width), 0, 1};
    vkCmdBlitImage(commandBuffer, from_uint64<VkImage>(sourceImage),
                   VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, destination.image,
                   VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region,
                   isDepth ? VK_FILTER_NEAREST : VK_FILTER_LINEAR);

    const ImageTransitionDesc postBlit[2] = {
        {from_uint64<VkImage>(sourceImage), range, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, kEngineRestingLayout,
         VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
         VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT},
        {destination.image, range, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, kEngineRestingLayout,
         VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
         VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT}
    };
    record_batched_transitions(commandBuffer, postBlit, 2);
    destination.layout = kEngineRestingLayout;
}

static void restore_motion_to_engine_resting_layout(const VkCommandBuffer commandBuffer) noexcept {
    const uint64_t motionImage = to_uint64(g_state.motionImage.image);
    record_layout_transition(commandBuffer, g_state.motionImage.image, image_range_of(false),
                             current_layout_of(motionImage), kEngineRestingLayout);
    note_layout_after_transition(motionImage, kEngineRestingLayout);
}

static void write_matrix(sl::float4x4& dest, const float* rowMajor) noexcept {
    for (uint32_t r = 0; r < 4; ++r) {
        sl::float4& row = dest[r];
        row.x = rowMajor[r * 4 + 0];
        row.y = rowMajor[r * 4 + 1];
        row.z = rowMajor[r * 4 + 2];
        row.w = rowMajor[r * 4 + 3];
    }
}

int32_t record_rr_options() noexcept {
    if (!sl_session_ready()) {
        return kNotInitialized;
    }

    sl::DLSSDOptions options{};
    options.mode = static_cast<sl::DLSSMode>(g_state.qualityMode + 1);
    options.outputWidth = g_state.outputWidth;
    options.outputHeight = g_state.outputHeight;
    options.colorBuffersHDR = sl::Boolean::eTrue;
    options.alphaUpscalingEnabled = sl::Boolean::eFalse;
    options.normalRoughnessMode = sl::DLSSDNormalRoughnessMode::ePacked;
    options.exposureScale = g_state.exposure > 0.0f ? g_state.exposure : 1.0f;
    write_matrix(options.worldToCameraView, g_state.rrWorldToCameraView);
    write_matrix(options.cameraViewToWorld, g_state.rrCameraViewToWorld);

    const sl::DLSSDPreset preset = static_cast<sl::DLSSDPreset>(g_state.rrPreset);
    options.dlaaPreset = preset;
    options.qualityPreset = preset;
    options.balancedPreset = preset;
    options.performancePreset = preset;
    options.ultraPerformancePreset = preset;

    const sl::Result result = slDLSSDSetOptions(sl::ViewportHandle{kSrViewportId}, options);
    return result == sl::Result::eOk ? kSuccess : static_cast<int32_t>(result);
}

int32_t prepare_rr(const McDlssRrPrepareInfo& info) noexcept {
    if (!sl_session_ready()) {
        return kNotInitialized;
    }
    if (info.enabled == 0) {
        store_rr_guides(info);
        return record_sr_options();
    }
    if (!valid_image(info.diffuse_albedo) || !valid_image(info.specular_albedo) ||
        !valid_image(info.normal_roughness) ||
        !valid_rr_preset(info.rr_preset)) {
        return kInvalidParameter;
    }
    if ((info.specular_motion.image != 0 || info.specular_motion.view != 0) &&
        (!valid_image(info.specular_motion) ||
         (info.specular_motion.format != VK_FORMAT_R16G16_SFLOAT &&
          info.specular_motion.format != VK_FORMAT_R32G32_SFLOAT))) {
        return kInvalidParameter;
    }
    if (!valid_rr_bias_current_color(info.bias_current_color)) {
        return kInvalidParameter;
    }
    store_rr_guides(info);
    return record_rr_options();
}

static void flip_matrix_y_in_place(float* rowMajor, const bool negateRow1,
                                   const bool negateCol1) noexcept {
    for (uint32_t r = 0; r < 4; ++r) {
        for (uint32_t c = 0; c < 4; ++c) {
            if ((negateRow1 && r == 1) != (negateCol1 && c == 1)) {
                rowMajor[r * 4 + c] = -rowMajor[r * 4 + c];
            }
        }
    }
}

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
    if (!g_state.fgCameraConstantsRecorded) {
        return kNotInitialized;
    }
    *out = g_state.lastFgCameraConstants;
    return kSuccess;
}

int32_t record_sr_evaluation(const McDlssEvaluateInfo& info,
                             VkCommandBuffer commandBuffer) noexcept {
    sl::FrameToken* frameToken = g_state.frameEligibility.token();
    if (frameToken == nullptr) {
        return kNotInitialized;
    }

    sl::Constants constants{};
    constants.jitterOffset = sl::float2(info.jitter.x, info.jitter.y);
    constants.mvecScale = sl::float2(0.5f, 0.5f);
    constants.reset = info.reset_history != 0 ? sl::Boolean::eTrue : sl::Boolean::eFalse;
    constants.cameraMotionIncluded = sl::Boolean::eTrue;
    constants.motionVectors3D = sl::Boolean::eFalse;
    constants.depthInverted = sl::Boolean::eTrue;
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
        restore_motion_to_engine_resting_layout(commandBuffer);
        return static_cast<int32_t>(result);
    }
    g_state.cameraConstantsRecorded = true;
    g_state.lastCameraConstants = info.camera;
    g_state.lastCameraConstants.jitter = info.jitter;

    if (g_state.frameEligibility.fgArmed()) {
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
        g_state.lastFgCameraConstants = info.camera;
        flip_matrix_y_in_place(g_state.lastFgCameraConstants.view_to_clip, false, true);
        flip_matrix_y_in_place(g_state.lastFgCameraConstants.clip_to_view, true, false);
        flip_matrix_y_in_place(g_state.lastFgCameraConstants.clip_to_prev_clip, true, true);
        flip_matrix_y_in_place(g_state.lastFgCameraConstants.prev_clip_to_clip, true, true);
        g_state.lastFgCameraConstants.jitter.x = info.jitter.x;
        g_state.lastFgCameraConstants.jitter.y = -info.jitter.y;
    }

    sl::ViewportHandle viewport{kSrViewportId};
    const sl::BaseStructure* inputs[] = {&viewport};
    result = slEvaluateFeature(g_state.rrArmed ? sl::kFeatureDLSS_RR : sl::kFeatureDLSS,
                               *frameToken, inputs, 1, commandBuffer);
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

    sl::Result result = sl::Result::eOk;
    if (!g_state.frameEligibility.hasToken()) {
        result = slGetNewFrameToken(g_state.frameEligibility.tokenSlot());
        if (result != sl::Result::eOk) {
            return static_cast<int32_t>(result);
        }
        result = slReflexSleep(*g_state.frameEligibility.token());
        if (result != sl::Result::eOk) {
            g_state.frameEligibility.invalidate();
            return static_cast<int32_t>(result);
        }
    }
    sl::FrameToken* frameToken = g_state.frameEligibility.token();

    const bool moduleImagesReady = images_match_configuration();

    const uint32_t renderWidth = g_state.renderWidth;
    const uint32_t renderHeight = g_state.renderHeight;
    const uint32_t outputWidth = g_state.outputWidth;
    const uint32_t outputHeight = g_state.outputHeight;

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
    const DlssOwnedImage& evaluationOutput =
        g_state.rrArmed ? g_state.rrOutputImage : g_state.outputImage;
    sl::Resource outputResource = make_tagged_resource(
        reinterpret_cast<void*>(evaluationOutput.image),
        reinterpret_cast<void*>(evaluationOutput.memory),
        reinterpret_cast<void*>(evaluationOutput.view),
        kDlssOutputLayout, outputWidth, outputHeight,
        static_cast<uint32_t>(g_state.rrArmed ? kRrOutputFormat : kOutputFormat));

    static sl::SubresourceRange colorRange{};
    colorRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    colorRange.baseMipLevel = 0;
    colorRange.levelCount = 1;
    colorRange.baseArrayLayer = 0;
    colorRange.layerCount = 1;
    static sl::SubresourceRange depthRange{};
    depthRange.aspectMask = depth_range_of(info.depth).aspectMask;
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

    const sl::Extent renderExtent{0, 0, renderWidth, renderHeight};
    const sl::Extent outputExtent{0, 0, outputWidth, outputHeight};
    sl::ResourceTag tags[14]{};
    uint32_t tagCount = 0;
    tags[tagCount++] = sl::ResourceTag(&colorResource, sl::kBufferTypeScalingInputColor,
                                       sl::ResourceLifecycle::eValidUntilPresent, &renderExtent);
    tags[tagCount++] = sl::ResourceTag(&depthResource, sl::kBufferTypeDepth,
                                       sl::ResourceLifecycle::eValidUntilPresent, &renderExtent);
    if (moduleImagesReady) {
        tags[tagCount++] = sl::ResourceTag(&motionResource, sl::kBufferTypeMotionVectors,
                                           sl::ResourceLifecycle::eValidUntilPresent, &renderExtent);
        tags[tagCount++] = sl::ResourceTag(&outputResource, sl::kBufferTypeScalingOutputColor,
                                           sl::ResourceLifecycle::eValidUntilPresent, &outputExtent);
    }

    sl::Resource albedoResource{};
    sl::Resource specularAlbedoResource{};
    sl::Resource normalRoughnessResource{};
    sl::Resource specularHitDistanceResource{};
    sl::Resource specularMotionResource{};
    static sl::SubresourceRange guideRange{};
    guideRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    guideRange.baseMipLevel = 0;
    guideRange.levelCount = 1;
    guideRange.baseArrayLayer = 0;
    guideRange.layerCount = 1;
    if (g_state.rrArmed) {
        albedoResource = make_tagged_resource(
            from_uint64<void*>(g_state.rrDiffuseAlbedo.image), nullptr,
            from_uint64<void*>(g_state.rrDiffuseAlbedo.view), kDlssInputLayout, renderWidth,
            renderHeight, g_state.rrDiffuseAlbedo.format);
        specularAlbedoResource = make_tagged_resource(
            from_uint64<void*>(g_state.rrSpecularAlbedo.image), nullptr,
            from_uint64<void*>(g_state.rrSpecularAlbedo.view), kDlssInputLayout, renderWidth,
            renderHeight, g_state.rrSpecularAlbedo.format);
        normalRoughnessResource = make_tagged_resource(
            from_uint64<void*>(g_state.rrNormalRoughness.image), nullptr,
            from_uint64<void*>(g_state.rrNormalRoughness.view), kDlssInputLayout, renderWidth,
            renderHeight, g_state.rrNormalRoughness.format);
        albedoResource.next = &guideRange;
        specularAlbedoResource.next = &guideRange;
        normalRoughnessResource.next = &guideRange;
        tags[tagCount++] = sl::ResourceTag(&albedoResource, sl::kBufferTypeAlbedo,
                                           sl::ResourceLifecycle::eValidUntilPresent, &renderExtent);
        tags[tagCount++] = sl::ResourceTag(&specularAlbedoResource, sl::kBufferTypeSpecularAlbedo,
                                           sl::ResourceLifecycle::eValidUntilPresent, &renderExtent);
        tags[tagCount++] = sl::ResourceTag(&normalRoughnessResource, sl::kBufferTypeNormalRoughness,
                                           sl::ResourceLifecycle::eValidUntilPresent, &renderExtent);
        if (valid_image(g_state.rrSpecularMotion) && g_state.rrSpecularMotion.image != 0) {
            specularMotionResource = make_tagged_resource(
                from_uint64<void*>(g_state.rrSpecularMotion.image), nullptr,
                from_uint64<void*>(g_state.rrSpecularMotion.view), kDlssInputLayout, renderWidth,
                renderHeight, g_state.rrSpecularMotion.format);
            specularMotionResource.next = &guideRange;
            tags[tagCount++] = sl::ResourceTag(&specularMotionResource, sl::kBufferTypeSpecularMotionVectors,
                                               sl::ResourceLifecycle::eValidUntilPresent, &renderExtent);
        } else if (valid_image(g_state.rrSpecularHitDistance) && g_state.rrSpecularHitDistance.image != 0) {
            specularHitDistanceResource = make_tagged_resource(
                from_uint64<void*>(g_state.rrSpecularHitDistance.image), nullptr,
                from_uint64<void*>(g_state.rrSpecularHitDistance.view), kDlssInputLayout, renderWidth,
                renderHeight, g_state.rrSpecularHitDistance.format);
            specularHitDistanceResource.next = &guideRange;
            tags[tagCount++] =
                sl::ResourceTag(&specularHitDistanceResource, sl::kBufferTypeSpecularHitDistance,
                                sl::ResourceLifecycle::eValidUntilPresent, &renderExtent);
        }
    }

    sl::Resource biasCurrentColorResource{};
    tags[tagCount++] = make_rr_bias_current_color_tag(biasCurrentColorResource);
    sl::Resource disocclusionMaskResource{};
    tags[tagCount++] = make_rr_disocclusion_mask_tag(disocclusionMaskResource);
    sl::Resource responsivityMaskResource{};
    tags[tagCount++] = make_rr_responsivity_mask_tag(responsivityMaskResource);

    result = slSetTagForFrame(*frameToken, sl::ViewportHandle{kSrViewportId}, tags, tagCount,
                              commandBuffer);
    if (result != sl::Result::eOk) {
        g_state.frameEligibility.invalidate();
        return static_cast<int32_t>(result);
    }
    g_state.frameEligibility.armSr(static_cast<uint32_t>(*frameToken));
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
    if (info.depth.format != static_cast<uint32_t>(VK_FORMAT_D32_SFLOAT) ||
        info.hudless.format != static_cast<uint32_t>(VK_FORMAT_R8G8B8A8_UNORM) ||
        info.ui.format != static_cast<uint32_t>(VK_FORMAT_R8G8B8A8_UNORM)) {
        return kInvalidParameter;
    }
    const VkCommandBuffer commandBuffer = from_uint64<VkCommandBuffer>(info.command_buffer);

    if (!g_state.fgOptions.recorded || !images_match_configuration()) {
        return kInvalidParameter;
    }

    sl::Result result = sl::Result::eOk;
    if (!g_state.frameEligibility.hasToken()) {
        result = slGetNewFrameToken(g_state.frameEligibility.tokenSlot());
        if (result != sl::Result::eOk) {
            return static_cast<int32_t>(result);
        }
        result = slReflexSleep(*g_state.frameEligibility.token());
        if (result != sl::Result::eOk) {
            g_state.frameEligibility.invalidate();
            return static_cast<int32_t>(result);
        }
    }
    sl::FrameToken* frameToken = g_state.frameEligibility.token();

    const uint32_t renderWidth = g_state.renderWidth;
    const uint32_t renderHeight = g_state.renderHeight;
    const uint32_t outputWidth = g_state.outputWidth;
    const uint32_t outputHeight = g_state.outputHeight;

    const uint32_t frameIndex = static_cast<uint32_t>(*frameToken);
    const bool afterSrTag = g_state.frameEligibility.srArmedAt(frameIndex);
    if (afterSrTag && g_state.frameEligibility.copiesNeededFor(frameIndex)) {
        record_flip_blit(commandBuffer, info.depth.image, g_state.fgDepthImage,
                         renderWidth, renderHeight, true);
        record_flip_blit(commandBuffer, info.hudless.image, g_state.fgHudlessImage,
                         outputWidth, outputHeight, false);
        record_flip_blit(commandBuffer, info.ui.image, g_state.fgUiImage,
                         outputWidth, outputHeight, false);
        g_state.frameEligibility.recordCopies(frameIndex);
    }

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

    const sl::Extent renderExtent{0, 0, renderWidth, renderHeight};
    const sl::Extent outputExtent{0, 0, outputWidth, outputHeight};
    sl::ResourceTag tags[4]{};
    tags[0] = sl::ResourceTag(&depthResource, sl::kBufferTypeDepth,
                              sl::ResourceLifecycle::eValidUntilPresent, &renderExtent);
    tags[1] = sl::ResourceTag(&hudlessResource, sl::kBufferTypeHUDLessColor,
                              sl::ResourceLifecycle::eValidUntilPresent, &outputExtent);
    tags[2] = sl::ResourceTag(&uiResource, sl::kBufferTypeUIColorAndAlpha,
                              sl::ResourceLifecycle::eValidUntilPresent, &outputExtent);
    tags[3] = sl::ResourceTag(&motionResource, sl::kBufferTypeMotionVectors,
                              sl::ResourceLifecycle::eValidUntilPresent, &renderExtent);

    result = slSetTagForFrame(*frameToken, sl::ViewportHandle{kFgViewportId}, tags, 4, commandBuffer);
    if (result != sl::Result::eOk) {
        g_state.frameEligibility.invalidate();
        return static_cast<int32_t>(result);
    }
    g_state.frameEligibility.armFg(static_cast<uint32_t>(*frameToken));
    return kSuccess;
}

}
