#include "internal/sl_dlss.h"

#include "internal/common.h"
#include "internal/ngx.h"
#include "internal/state.h"

#include <sl.h>
#include <sl_core_api.h>
#include <sl_dlss.h>

/*
 * The Streamline DLSS surface of the module, layered above state like the NGX unit. The ABI
 * keeps the NGX-valued parameters the rest of the module already validates against; the
 * mapping to sl:: types happens here, where the Streamline headers live.
 */
namespace mc_dlss {

bool sl_session_ready() noexcept {
    return g_state.streamlineInitialized && g_state.proxyDevice != 0;
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

// Builds the sl::Resource description for one tagged image. `native` is the VkImage and `view`
// the VkImageView the ABI carried (or this module allocated), `state` is the layout the image
// rests in when the frame is tagged, and `width`/`height`/`format` are the dimensions and
// format the tag names - the configured render size for the inputs, the output size for the
// output. All four resources are single-level, single-layer 2D images.
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

int32_t tag_sr_resources(const McDlssTagInfo& info) noexcept {
    if (!sl_session_ready()) {
        return kNotInitialized;
    }
    if (info.command_buffer == 0 || !valid_image(info.color) || !valid_image(info.depth)) {
        return kInvalidParameter;
    }

    sl::FrameToken* frameToken = nullptr;
    sl::Result result = slGetNewFrameToken(frameToken);
    if (result != sl::Result::eOk) {
        return static_cast<int32_t>(result);
    }

    // The engine's colour and depth are the frame's inputs, so they tag from the first frame
    // on. The module's motion and output images can only tag once they exist at the configured
    // size - there is no NGX initialize in the SL path to acquire them early - so they are
    // added when (and only when) images_match_configuration holds.
    const bool moduleImagesReady = images_match_configuration();
    const uint32_t numTags = moduleImagesReady ? 4 : 2;

    const uint32_t renderWidth = g_state.renderWidth;
    const uint32_t renderHeight = g_state.renderHeight;
    const uint32_t outputWidth = g_state.outputWidth;
    const uint32_t outputHeight = g_state.outputHeight;

    // The engine's images rest where Minecraft leaves its textures, and the module's own
    // images rest in whatever layout the last recorded barrier left them in.
    sl::Resource colorResource = make_sr_resource(
        from_uint64<void*>(info.color.image), nullptr, from_uint64<void*>(info.color.view),
        kEngineRestingLayout, renderWidth, renderHeight, info.color.format);
    sl::Resource depthResource = make_sr_resource(
        from_uint64<void*>(info.depth.image), nullptr, from_uint64<void*>(info.depth.view),
        kEngineRestingLayout, renderWidth, renderHeight, info.depth.format);
    sl::Resource motionResource = make_sr_resource(
        reinterpret_cast<void*>(g_state.motionImage.image),
        reinterpret_cast<void*>(g_state.motionImage.memory),
        reinterpret_cast<void*>(g_state.motionImage.view),
        current_layout_of(to_uint64(g_state.motionImage.image)), renderWidth, renderHeight,
        static_cast<uint32_t>(kMotionFormat));
    sl::Resource outputResource = make_sr_resource(
        reinterpret_cast<void*>(g_state.outputImage.image),
        reinterpret_cast<void*>(g_state.outputImage.memory),
        reinterpret_cast<void*>(g_state.outputImage.view),
        current_layout_of(to_uint64(g_state.outputImage.image)), outputWidth, outputHeight,
        static_cast<uint32_t>(kOutputFormat));

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
        tags[2] = sl::ResourceTag(&motionResource, sl::kBufferTypeMotionVectors,
                                  sl::ResourceLifecycle::eValidUntilPresent, &renderExtent);
        tags[3] = sl::ResourceTag(&outputResource, sl::kBufferTypeScalingOutputColor,
                                  sl::ResourceLifecycle::eValidUntilPresent, &outputExtent);
    }

    // The command buffer is the caller's shared recording: slSetTagForFrame takes it as an
    // opaque pointer and this module only ever records on it, never submits.
    VkCommandBuffer commandBuffer = from_uint64<VkCommandBuffer>(info.command_buffer);
    result = slSetTagForFrame(*frameToken, sl::ViewportHandle{0}, tags, numTags, &commandBuffer);
    return result == sl::Result::eOk ? kSuccess : static_cast<int32_t>(result);
}

} // namespace mc_dlss
