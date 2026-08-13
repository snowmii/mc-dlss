#include "internal/sl_dlss.h"

#include "internal/common.h"
#include "internal/ngx.h"
#include "internal/state.h"
#include "internal/timing.h"

#include <sl.h>
#include <sl_core_api.h>
#include <sl_dlss.h>
#include <sl_dlss_g.h>

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

    // The record is fixed at the contract's single multiplier: 2x. Every field is stated
    // explicitly rather than inherited from the SDK defaults, because each one is a decision
    // the guide calls out - retained resources for seamless pause/menu suspension, UI
    // recomposition for the split's separate HUD-less/UI inputs, and the Vulkan-only
    // eBlockNoClientQueues queue-parallelism mode, which lets DLSS-G run on its own queues
    // instead of blocking the presenting queue. The host's obligation under that mode -
    // waiting on DLSSGState::inputsProcessingCompletionFence before modifying or destroying
    // the tagged inputs of a previously presented frame - is the frame-side discipline the
    // M-11 present slice implements. The guide's set-options call validates little of this
    // and the wrong form of any field records silently, so the record is a dense contract,
    // not a convenience.
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

    // The viewport is the same one the SR options, tags, and evaluation record against: the
    // frame's resources tag on viewport 0 and the options must name the viewport they apply
    // to.
    const sl::Result result = slDLSSGSetOptions(sl::ViewportHandle{0}, options);
    return result == sl::Result::eOk ? kSuccess : static_cast<int32_t>(result);
}

// Builds the sl::Resource description for one tagged image. `native` is the VkImage and `view`
// the VkImageView the ABI carried (or this module allocated), `state` is the layout the
// evaluation reads or writes the image in (the layout this module's own transitions establish
// immediately before it), and `width`/`height`/`format` are the dimensions and format the tag
// names - the configured render size for the inputs, the output size for the output. All four
// resources are single-level, single-layer 2D images.
sl::Resource make_sr_resource(void* native, void* memory, void* view, uint32_t state,
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
        g_state.frameToken = nullptr;
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
    // The frame consumed its token whether the evaluation succeeded or failed: a failed frame
    // has no history the next one could reuse, and the next tag must obtain a fresh token.
    g_state.frameToken = nullptr;
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
    sl::Resource colorResource = make_sr_resource(
        from_uint64<void*>(info.color.image), nullptr, from_uint64<void*>(info.color.view),
        kDlssInputLayout, renderWidth, renderHeight, info.color.format);
    sl::Resource depthResource = make_sr_resource(
        from_uint64<void*>(info.depth.image), nullptr, from_uint64<void*>(info.depth.view),
        kDlssInputLayout, renderWidth, renderHeight, info.depth.format);
    sl::Resource motionResource = make_sr_resource(
        reinterpret_cast<void*>(g_state.motionImage.image),
        reinterpret_cast<void*>(g_state.motionImage.memory),
        reinterpret_cast<void*>(g_state.motionImage.view),
        kDlssInputLayout, renderWidth, renderHeight,
        static_cast<uint32_t>(kMotionFormat));
    sl::Resource outputResource = make_sr_resource(
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
        g_state.frameToken = nullptr;
        return static_cast<int32_t>(result);
    }
    return kSuccess;
}

} // namespace mc_dlss
