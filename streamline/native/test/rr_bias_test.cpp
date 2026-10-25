// Device-free RR guide state/tag/barrier contracts. No SDK runtime or GPU handles used.
#include "internal/state.h"
#include "doctest.h"

using namespace mc_dlss;

namespace {

McDlssRrPrepareInfo rr_with_bias() {
    McDlssRrPrepareInfo info{};
    info.enabled = 1;
    info.rr_preset = 6;
    info.diffuse_albedo = {1, 2, VK_FORMAT_R16G16B16A16_SFLOAT};
    info.specular_albedo = {3, 4, VK_FORMAT_R16G16B16A16_SFLOAT};
    info.normal_roughness = {5, 6, VK_FORMAT_R16G16B16A16_SFLOAT};
    info.specular_motion = {7, 8, VK_FORMAT_R16G16_SFLOAT};
    info.bias_current_color = {9, 10, VK_FORMAT_R8_UNORM};
    info.world_to_camera_view[0] = 2.0f;
    info.camera_view_to_world[0] = 0.5f;
    return info;
}

} // namespace

TEST_CASE("RR bias validates optional handles and pinned SDK named formats") {
    CHECK(valid_rr_bias_current_color({}));
    // Format metadata alone does not make an absent image present.
    CHECK(valid_rr_bias_current_color({0, 0, VK_FORMAT_R8_UNORM}));
    for (const VkFormat format : {VK_FORMAT_R8_UNORM, VK_FORMAT_R16_SFLOAT,
                                 VK_FORMAT_R8G8B8A8_UNORM}) {
        CHECK(valid_rr_bias_current_color({1, 2, static_cast<uint32_t>(format)}));
    }
    CHECK_FALSE(valid_rr_bias_current_color({0, 2, VK_FORMAT_R8_UNORM}));
    CHECK_FALSE(valid_rr_bias_current_color({1, 0, VK_FORMAT_R8_UNORM}));
    CHECK_FALSE(valid_rr_bias_current_color({1, 2, VK_FORMAT_UNDEFINED}));
    CHECK_FALSE(valid_rr_bias_current_color({1, 2, VK_FORMAT_R32_SFLOAT}));
    CHECK_FALSE(valid_rr_bias_current_color({1, 2, VK_FORMAT_R16G16_SFLOAT}));
}

TEST_CASE("RR preparation copies borrowed bias and absence disable reset drop bindings") {
    reset_state();
    auto info = rr_with_bias();
    store_rr_guides(info);
    CHECK(g_state.rrArmed);
    CHECK(g_state.rrPreset == 6);
    CHECK(g_state.rrBiasCurrentColor.view == 9);
    CHECK(g_state.rrBiasCurrentColor.image == 10);
    CHECK(g_state.rrBiasCurrentColor.format == VK_FORMAT_R8_UNORM);
    CHECK(g_state.rrSpecularMotion.image == 8);
    CHECK(g_state.rrWorldToCameraView[0] == 2.0f);
    CHECK(g_state.rrCameraViewToWorld[0] == 0.5f);
    info.bias_current_color = {};
    CHECK(g_state.rrBiasCurrentColor.image == 10); // Copied, not retained ABI pointer.
    store_rr_guides(info);
    CHECK(g_state.rrArmed);
    CHECK(g_state.rrBiasCurrentColor.image == 0);

    info = rr_with_bias();
    store_rr_guides(info);
    info.enabled = 0; // Populated disabled request must not retain any guide.
    store_rr_guides(info);
    CHECK_FALSE(g_state.rrArmed);
    CHECK(g_state.rrBiasCurrentColor.image == 0);
    CHECK(g_state.rrDiffuseAlbedo.image == 0);
    CHECK(g_state.rrSpecularAlbedo.image == 0);
    CHECK(g_state.rrNormalRoughness.image == 0);
    CHECK(g_state.rrSpecularHitDistance.image == 0);
    CHECK(g_state.rrSpecularMotion.image == 0);

    info.enabled = 1;
    store_rr_guides(info);
    reset_state();
    CHECK_FALSE(g_state.rrArmed);
    CHECK(g_state.rrBiasCurrentColor.image == 0);
    CHECK(g_state.rrBiasCurrentColor.view == 0);
    CHECK(g_state.rrBiasCurrentColor.format == VK_FORMAT_UNDEFINED);
}

TEST_CASE("RR bias tags current guide and emits SDK-safe empty resource for SR or absence") {
    reset_state();
    g_state.renderWidth = 960;
    g_state.renderHeight = 540;
    auto info = rr_with_bias();
    store_rr_guides(info);
    sl::Resource resource{};
    auto tag = make_rr_bias_current_color_tag(resource);
    CHECK(tag.type == 29);
    CHECK(tag.resource == &resource);
    CHECK(tag.lifecycle == sl::ResourceLifecycle::eValidUntilPresent);
    CHECK(tag.extent.width == 960);
    CHECK(tag.extent.height == 540);
    CHECK(resource.native == from_uint64<void*>(10));
    CHECK(resource.view == from_uint64<void*>(9));
    CHECK(resource.width == 960);
    CHECK(resource.height == 540);
    CHECK(resource.nativeFormat == VK_FORMAT_R8_UNORM);
    CHECK(resource.state == kDlssInputLayout);
    REQUIRE(resource.next != nullptr);
    const auto* range = static_cast<const sl::SubresourceRange*>(resource.next);
    CHECK(range->aspectMask == VK_IMAGE_ASPECT_COLOR_BIT);
    CHECK(range->levelCount == 1);
    CHECK(range->layerCount == 1);

    sl::Resource disocclusionResource{};
    auto disocclusionTag = make_rr_disocclusion_mask_tag(disocclusionResource);
    CHECK(disocclusionTag.type == sl::kBufferTypeDisocclusionMask);
    CHECK(disocclusionTag.type == 11);
    CHECK(disocclusionTag.resource == &disocclusionResource);
    CHECK(disocclusionTag.lifecycle == sl::ResourceLifecycle::eValidUntilPresent);
    CHECK(disocclusionTag.extent.width == 960);
    CHECK(disocclusionTag.extent.height == 540);
    CHECK(disocclusionResource.native == from_uint64<void*>(10));
    CHECK(disocclusionResource.view == from_uint64<void*>(9));
    CHECK(disocclusionResource.width == 960);
    CHECK(disocclusionResource.height == 540);
    CHECK(disocclusionResource.nativeFormat == VK_FORMAT_R8_UNORM);
    CHECK(disocclusionResource.state == kDlssInputLayout);

    // Reuse resource storage: empty tag must overwrite all previous binding data.
    info.bias_current_color = {};
    store_rr_guides(info);
    tag = make_rr_bias_current_color_tag(resource);
    CHECK(tag.type == sl::kBufferTypeBiasCurrentColorHint);
    CHECK(tag.resource == &resource); // Not a null Resource pointer that local lookup dereferences.
    CHECK(resource.native == nullptr);
    CHECK(resource.view == nullptr);
    CHECK(resource.next == nullptr);
    CHECK(resource.flags == 0);

    disocclusionTag = make_rr_disocclusion_mask_tag(disocclusionResource);
    CHECK(disocclusionTag.type == sl::kBufferTypeDisocclusionMask);
    CHECK(disocclusionTag.resource == &disocclusionResource);
    CHECK(disocclusionResource.native == nullptr);
    CHECK(disocclusionResource.view == nullptr);
    CHECK(disocclusionResource.next == nullptr);
    CHECK(disocclusionResource.flags == 0);

    store_rr_guides(rr_with_bias());
    g_state.rrArmed = false; // SR fallback must clear even if borrowed state still exists.
    tag = make_rr_bias_current_color_tag(resource);
    CHECK(tag.resource == &resource);
    CHECK(resource.native == nullptr);
    CHECK(resource.view == nullptr);

    disocclusionTag = make_rr_disocclusion_mask_tag(disocclusionResource);
    CHECK(disocclusionTag.resource == &disocclusionResource);
    CHECK(disocclusionResource.native == nullptr);
    CHECK(disocclusionResource.view == nullptr);
    reset_state();
}

TEST_CASE("RR does not alias bias or disocclusion as signed responsivity") {
    reset_state();
    g_state.renderWidth = 960;
    g_state.renderHeight = 540;
    for (const VkFormat format : {VK_FORMAT_R8_UNORM, VK_FORMAT_R8G8B8A8_UNORM,
                                 VK_FORMAT_R16_SFLOAT}) {
        auto info = rr_with_bias();
        info.bias_current_color.format = format;
        store_rr_guides(info);
        sl::Resource resource{};
        make_rr_bias_current_color_tag(resource); // Reused storage must also be cleared.
        REQUIRE(resource.native != nullptr);
        const auto tag = make_rr_responsivity_mask_tag(resource);
        CHECK(tag.type == sl::kBufferTypeResponsivityMask);
        CHECK(tag.resource == &resource);
        CHECK(resource.native == nullptr);
        CHECK(resource.view == nullptr);
        CHECK(resource.next == nullptr);
        CHECK(resource.flags == 0);
    }
    reset_state();
}

TEST_CASE("RR bias evaluation barriers read then restore borrowed guide entry layout") {
    reset_state();
    store_rr_guides(rr_with_bias());
    const auto pre = rr_bias_current_color_transition(kEngineRestingLayout, false);
    const auto post = rr_bias_current_color_transition(kEngineRestingLayout, true);
    CHECK(pre.image == from_uint64<VkImage>(10));
    CHECK(post.image == pre.image);
    CHECK(pre.oldLayout == kEngineRestingLayout);
    CHECK(pre.newLayout == kDlssInputLayout);
    CHECK(post.oldLayout == pre.newLayout);
    CHECK(post.newLayout == pre.oldLayout);
    CHECK(pre.subresourceRange.aspectMask == VK_IMAGE_ASPECT_COLOR_BIT);
    CHECK(pre.subresourceRange.levelCount == 1);
    CHECK(pre.subresourceRange.layerCount == 1);
    CHECK(pre.srcStage == VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
    CHECK(pre.dstStage == pre.srcStage);
    CHECK(post.srcStage == pre.srcStage);
    CHECK(post.dstStage == pre.dstStage);
    CHECK(pre.srcAccess == (VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT));
    CHECK(pre.dstAccess == pre.srcAccess);
    CHECK(post.srcAccess == pre.srcAccess);
    CHECK(post.dstAccess == pre.dstAccess);

    auto info = rr_with_bias();
    info.bias_current_color = {};
    store_rr_guides(info);
    CHECK(rr_bias_current_color_transition(kEngineRestingLayout, false).image == VK_NULL_HANDLE);
    CHECK(rr_bias_current_color_transition(kEngineRestingLayout, true).image == VK_NULL_HANDLE);
    store_rr_guides(rr_with_bias());
    g_state.rrArmed = false;
    CHECK(rr_bias_current_color_transition(kEngineRestingLayout, false).image == VK_NULL_HANDLE);
    CHECK(rr_bias_current_color_transition(kEngineRestingLayout, true).image == VK_NULL_HANDLE);
    reset_state();
}
