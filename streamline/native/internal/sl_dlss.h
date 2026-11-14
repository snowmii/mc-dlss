#ifndef MC_DLSS_INTERNAL_SL_DLSS_H
#define MC_DLSS_INTERNAL_SL_DLSS_H

#include "internal/state.h"

#include <cstdint>
#include <sl_reflex.h>

namespace mc_dlss {

bool sl_session_ready() noexcept;

void shutdown_streamline() noexcept;

int32_t is_feature_supported_sl(uint32_t feature, uint64_t vkPhysicalDevice) noexcept;

int32_t query_optimal_dimensions_sl(uint32_t outputWidth, uint32_t outputHeight,
                                    uint32_t qualityMode, uint32_t* renderWidth,
                                    uint32_t* renderHeight) noexcept;

int32_t query_optimal_dimensions_rr_sl(uint32_t outputWidth, uint32_t outputHeight,
                                       uint32_t qualityMode, uint32_t* renderWidth,
                                       uint32_t* renderHeight) noexcept;

int32_t record_sr_options() noexcept;

int32_t record_rr_options() noexcept;

int32_t prepare_rr(const McDlssRrPrepareInfo& info) noexcept;

int32_t record_fg_options(uint32_t numBackBuffers) noexcept;

int32_t record_fg_mode(uint32_t fgEnabled) noexcept;

int32_t record_present_handoff() noexcept;
int32_t present_start() noexcept;
int32_t present_end() noexcept;

int32_t install_pcl_window(uint64_t hwnd) noexcept;
int32_t reflex_input_sample() noexcept;
int32_t reflex_marker(uint32_t markerType) noexcept;

int32_t query_reflex_markers(uint32_t* typeCounts, uint32_t* eventCount, uint32_t* events,
                             uint32_t eventsCapacity) noexcept;

inline sl::ReflexOptions make_reflex_options() noexcept {
    sl::ReflexOptions options{};
    options.mode = static_cast<sl::ReflexMode>(g_state.reflexMode);
    options.frameLimitUs = g_state.reflexFrameLimitUs;
    return options;
}

int32_t record_reflex_options() noexcept;

int32_t record_reflex_frame_limit(uint32_t frameLimitUs) noexcept;

int32_t record_reflex_mode(uint32_t mode) noexcept;

int32_t query_reflex_state(uint32_t* lowLatencyAvailable, uint32_t* latencyReportAvailable) noexcept;

int32_t query_reflex_options(uint32_t* mode, uint32_t* setOptionsCalls) noexcept;

int32_t query_present_markers(uint32_t* startCount, uint32_t* endCount, uint32_t* eventCount,
                              uint32_t* events, uint32_t eventsCapacity) noexcept;

int32_t tag_sr_resources(const McDlssTagInfo& info) noexcept;

int32_t tag_fg_resources(const McDlssFgTagInfo& info) noexcept;

int32_t record_sr_evaluation(const McDlssEvaluateInfo& info,
                             VkCommandBuffer commandBuffer) noexcept;

int32_t query_camera_constants(McDlssCameraConstants* out) noexcept;

int32_t query_fg_camera_constants(McDlssCameraConstants* out) noexcept;

int32_t wait_fg_inputs_idle() noexcept;

int32_t wait_fg_inputs_value(uint64_t vkDevice, uint64_t semaphore, uint64_t value) noexcept;

int32_t query_fg_state(uint32_t* status, uint32_t* numFramesPresented,
                       uint64_t* lastPresentInputsProcessingFenceValue,
                       uint64_t* inputsProcessingCompletionFence) noexcept;

int32_t query_fg_vsync_support(uint32_t* vsyncSupported) noexcept;

}

#endif
