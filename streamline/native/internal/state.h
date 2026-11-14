#ifndef MC_DLSS_INTERNAL_STATE_H
#define MC_DLSS_INTERNAL_STATE_H

#include "internal/common.h"

#include "mc_dlss.h"
#include <sl_core_types.h>

#include <mutex>

namespace mc_dlss {

constexpr uint32_t kMotionDescriptorRing = 4;

constexpr uint32_t kSrViewportId = 0;
constexpr uint32_t kFgViewportId = 1;

enum PresentMarkerType : uint32_t {
    kPresentMarkerStart = 0,
    kPresentMarkerEnd = 1,
    kPresentMarkerTypeCount = 2,
};

enum ReflexMarkerType : uint32_t {
    kReflexMarkerInputSample = 0,
    kReflexMarkerSimulationStart = 1,
    kReflexMarkerSimulationEnd = 2,
    kReflexMarkerRenderSubmitStart = 3,
    kReflexMarkerRenderSubmitEnd = 4,
    kReflexMarkerTypeCount = 5,
};

struct MarkerEvent {
    uint32_t type;
    uint32_t frameIndex;
};

constexpr uint32_t kMarkerLogSize = 16;
constexpr uint32_t kMarkerTypeCapacity = kReflexMarkerTypeCount;

struct MarkerLog {
    uint32_t typeCounts[kMarkerTypeCapacity] = {};
    uint32_t eventCount = 0;
    MarkerEvent events[kMarkerLogSize] = {};

    void record(uint32_t type, const sl::FrameToken* frameToken) noexcept;

    int32_t read(uint32_t* outTypeCounts, uint32_t typeCount, uint32_t* outEventCount,
                 uint32_t* outEvents, uint32_t eventsCapacity) const noexcept;
};

class FrameEligibility {
public:
    sl::FrameToken* token() const noexcept { return token_; }
    bool hasToken() const noexcept { return token_ != nullptr; }
    sl::FrameToken*& tokenSlot() noexcept { return token_; }
    void releaseToken() noexcept { token_ = nullptr; }

    void armSr(uint32_t frameIndex) noexcept;
    void armFg(uint32_t frameIndex) noexcept;
    bool srArmed() const noexcept { return srRecorded_; }
    bool fgArmed() const noexcept { return fgRecorded_; }
    bool srArmedAt(uint32_t frameIndex) const noexcept;
    bool tagIndexes(uint32_t* srFrameIndex, uint32_t* fgFrameIndex) const noexcept;

    bool tagSetComplete() const noexcept;
    bool handoffEligible() const noexcept { return tagSetComplete() && token_ != nullptr; }
    void consumeForHandoff() noexcept;

    bool presentArmed() const noexcept { return presentArmed_; }
    bool presentStartEmitted() const noexcept { return presentStartEmitted_; }
    bool presentStartPending() const noexcept;
    void markPresentStartEmitted() noexcept { presentStartEmitted_ = true; }
    void consumePresent() noexcept;

    bool copiesNeededFor(uint32_t frameIndex) const noexcept;
    void recordCopies(uint32_t frameIndex) noexcept;

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

struct FgOptionsRecord {
    bool recorded = false;
    uint32_t numBackBuffers = 0;
    uint32_t numFramesToGenerate = 1;
};

struct DlssOwnedImage {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkImageLayout layout = VK_IMAGE_LAYOUT_UNDEFINED;
};

struct DlssMotionPass {
    VkShaderModule shader = VK_NULL_HANDLE;
    VkSampler sampler = VK_NULL_HANDLE;
    VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet sets[kMotionDescriptorRing] = {};
    uint32_t nextSet = 0;
    uint64_t boundViews[kMotionDescriptorRing][4] = {};
};

struct DlssState {
    bool sessionReady = false;
    bool streamlineInitialized = false;
    uint64_t instanceValue = 0;
    uint64_t physicalDeviceValue = 0;
    uint64_t deviceValue = 0;
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
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
    uint32_t rrPreset = 0;
    FgOptionsRecord fgOptions;
    bool reflexOptionsRecorded = false;
    uint32_t reflexMode = 1;
    uint32_t reflexSetOptionsCalls = 0;
    uint32_t reflexFrameLimitUs = 0;
    DlssOwnedImage motionImage;
    DlssOwnedImage outputImage;
    DlssOwnedImage rrOutputImage;
    DlssOwnedImage fgDepthImage;
    DlssOwnedImage fgHudlessImage;
    DlssOwnedImage fgUiImage;
    DlssOwnedImage fgMotionImage;
    DlssMotionPass motionPass;
    uint32_t imagesRenderWidth = 0;
    uint32_t imagesRenderHeight = 0;
    uint32_t imagesOutputWidth = 0;
    uint32_t imagesOutputHeight = 0;
    FrameEligibility frameEligibility;
    MarkerLog presentMarkers;
    MarkerLog reflexMarkers;
    bool cameraConstantsRecorded = false;
    McDlssCameraConstants lastCameraConstants{};
    bool fgCameraConstantsRecorded = false;
    McDlssCameraConstants lastFgCameraConstants{};
    bool rrArmed = false;
    McDlssImage rrDiffuseAlbedo{};
    McDlssImage rrSpecularAlbedo{};
    McDlssImage rrNormalRoughness{};
    McDlssImage rrSpecularHitDistance{};
    McDlssImage rrSpecularMotion{};
    McDlssImage rrBiasCurrentColor{};
    float rrWorldToCameraView[16]{};
    float rrCameraViewToWorld[16]{};
    float exposure = 1.0f;
    int32_t toneMapOperator = 0;
};

extern DlssState g_state;
extern std::mutex g_mutex;

void reset_state() noexcept;

void store_rr_guides(const McDlssRrPrepareInfo& info) noexcept;

sl::ResourceTag make_rr_bias_current_color_tag(sl::Resource& resource) noexcept;
sl::ResourceTag make_rr_disocclusion_mask_tag(sl::Resource& resource) noexcept;
sl::ResourceTag make_rr_responsivity_mask_tag(sl::Resource& resource) noexcept;

int32_t record_fg_multiplier(uint32_t numFramesToGenerate) noexcept;

int32_t query_fg_multiplier(uint32_t* current, uint32_t* max) noexcept;

bool images_match_configuration() noexcept;

void wait_device_idle() noexcept;

struct ImageTransitionDesc {
    VkImage image = VK_NULL_HANDLE;
    VkImageSubresourceRange subresourceRange{};
    VkImageLayout oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VkImageLayout newLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VkPipelineStageFlags srcStage = 0;
    VkPipelineStageFlags dstStage = 0;
    VkAccessFlags srcAccess = 0;
    VkAccessFlags dstAccess = 0;
};

ImageTransitionDesc rr_bias_current_color_transition(VkImageLayout entryLayout,
                                                     bool restore) noexcept;

void record_batched_transitions(VkCommandBuffer commandBuffer,
                                const ImageTransitionDesc* transitions,
                                uint32_t count) noexcept;

void record_layout_transition(VkCommandBuffer commandBuffer, VkImage image,
                              const VkImageSubresourceRange& subresourceRange,
                              VkImageLayout oldLayout, VkImageLayout newLayout) noexcept;

VkImageLayout current_layout_of(uint64_t image) noexcept;

void note_layout_after_transition(uint64_t image, VkImageLayout layout) noexcept;

}

#endif
