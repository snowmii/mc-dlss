#include "internal/state.h"
#include "internal/sl_dlss.h"

#include "doctest.h"

#include <cstdint>

using namespace mc_dlss;

namespace {

VkImageView fake_view(const std::uintptr_t value) {
	return reinterpret_cast<VkImageView>(value);
}

}

TEST_CASE("reset_state returns every recorded field to its default") {
	reset_state();
	g_state.sessionReady = true;
	g_state.streamlineInitialized = true;
	g_state.deviceValue = 0x1234;
	g_state.proxyComputeQueueIndex = 3;
	g_state.outputWidth = 1920;
	g_state.outputHeight = 1080;
	g_state.renderWidth = 960;
	g_state.renderHeight = 540;
	g_state.qualityMode = 2;
	g_state.fgOptions.recorded = true;
	g_state.fgOptions.numBackBuffers = 3;
	g_state.fgOptions.numFramesToGenerate = 3;
	g_state.reflexOptionsRecorded = true;
	g_state.reflexMode = 0;
	g_state.reflexSetOptionsCalls = 4;
	g_state.reflexFrameLimitUs = 5000;
	g_state.motionImage.image = reinterpret_cast<VkImage>(std::uintptr_t{0x1234});
	g_state.motionImage.view = fake_view(1);
	g_state.motionImage.layout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
	g_state.outputImage.layout = VK_IMAGE_LAYOUT_GENERAL;
	g_state.imagesOutputWidth = 1920;
	g_state.presentMarkers.eventCount = 4;
	g_state.presentMarkers.typeCounts[mc_dlss::kPresentMarkerStart] = 2;
	g_state.reflexMarkers.eventCount = 7;
	g_state.reflexMarkers.typeCounts[mc_dlss::kReflexMarkerInputSample] = 5;
	g_state.frameEligibility.tokenSlot() = reinterpret_cast<sl::FrameToken*>(std::uintptr_t{1});
	g_state.frameEligibility.armSr(9);
	g_state.frameEligibility.armFg(9);
	g_state.frameEligibility.consumeForHandoff();
	g_state.frameEligibility.markPresentStartEmitted();
	g_state.frameEligibility.recordCopies(11);
	g_state.cameraConstantsRecorded = true;
	g_state.lastCameraConstants.pos[0] = 0.5f;

	reset_state();

	const mc_dlss::DlssState defaults{};
	CHECK(g_state.sessionReady == defaults.sessionReady);
	CHECK(g_state.streamlineInitialized == defaults.streamlineInitialized);
	CHECK(g_state.deviceValue == defaults.deviceValue);
	CHECK(g_state.proxyComputeQueueIndex == defaults.proxyComputeQueueIndex);
	CHECK(g_state.outputWidth == defaults.outputWidth);
	CHECK(g_state.outputHeight == defaults.outputHeight);
	CHECK(g_state.renderWidth == defaults.renderWidth);
	CHECK(g_state.renderHeight == defaults.renderHeight);
	CHECK(g_state.qualityMode == defaults.qualityMode);
	CHECK(g_state.fgOptions.recorded == defaults.fgOptions.recorded);
	CHECK(g_state.fgOptions.numBackBuffers == defaults.fgOptions.numBackBuffers);
	CHECK(g_state.fgOptions.numFramesToGenerate == 1);
	CHECK(g_state.reflexOptionsRecorded == defaults.reflexOptionsRecorded);
	CHECK(g_state.reflexMode == defaults.reflexMode);
	CHECK(g_state.reflexSetOptionsCalls == defaults.reflexSetOptionsCalls);
	CHECK(g_state.reflexFrameLimitUs == defaults.reflexFrameLimitUs);
	CHECK(g_state.motionImage.image == VK_NULL_HANDLE);
	CHECK(g_state.motionImage.view == VK_NULL_HANDLE);
	CHECK(g_state.motionImage.layout == VK_IMAGE_LAYOUT_UNDEFINED);
	CHECK(g_state.outputImage.layout == VK_IMAGE_LAYOUT_UNDEFINED);
	CHECK(g_state.rrOutputImage.image == VK_NULL_HANDLE);
	CHECK(g_state.rrOutputImage.layout == VK_IMAGE_LAYOUT_UNDEFINED);
	CHECK(g_state.imagesOutputWidth == defaults.imagesOutputWidth);
	CHECK(g_state.presentMarkers.eventCount == 0);
	CHECK(g_state.presentMarkers.typeCounts[mc_dlss::kPresentMarkerStart] == 0);
	CHECK(g_state.reflexMarkers.eventCount == 0);
	CHECK(g_state.reflexMarkers.typeCounts[mc_dlss::kReflexMarkerInputSample] == 0);
	CHECK_FALSE(g_state.frameEligibility.hasToken());
	CHECK_FALSE(g_state.frameEligibility.presentArmed());
	CHECK_FALSE(g_state.frameEligibility.presentStartEmitted());
	CHECK_FALSE(g_state.frameEligibility.srArmed());
	CHECK_FALSE(g_state.frameEligibility.fgArmed());
	CHECK(g_state.frameEligibility.copiesNeededFor(11));
	CHECK(g_state.cameraConstantsRecorded == defaults.cameraConstantsRecorded);
	CHECK(g_state.lastCameraConstants.pos[0] == defaults.lastCameraConstants.pos[0]);
}

TEST_CASE("Reflex off reaches the SDK payload and survives frame-limit changes") {
    reset_state();
    g_state.reflexMode = 2;
    CHECK(make_reflex_options().mode == sl::ReflexMode::eLowLatencyWithBoost);
    g_state.reflexMode = 0;
    CHECK(make_reflex_options().mode == sl::ReflexMode::eOff);
    g_state.reflexFrameLimitUs = 5000;
    const auto capped = make_reflex_options();
    CHECK(capped.mode == sl::ReflexMode::eOff);
    CHECK(capped.frameLimitUs == 5000);
    g_state.reflexFrameLimitUs = 0;
    const auto uncapped = make_reflex_options();
    CHECK(uncapped.mode == sl::ReflexMode::eOff);
    CHECK(uncapped.frameLimitUs == 0);
    reset_state();
}

TEST_CASE("images_match_configuration requires views and matching dimensions") {
	reset_state();
	g_state.motionImage.view = fake_view(1);
	g_state.outputImage.view = fake_view(2);
	g_state.rrOutputImage.view = fake_view(3);
	g_state.imagesRenderWidth = g_state.renderWidth = 960;
	g_state.imagesRenderHeight = g_state.renderHeight = 540;
	g_state.imagesOutputWidth = g_state.outputWidth = 1920;
	g_state.imagesOutputHeight = g_state.outputHeight = 1080;
	CHECK(images_match_configuration());

	g_state.imagesOutputHeight = 1079;
	CHECK_FALSE(images_match_configuration());
	g_state.imagesOutputHeight = 1080;
	g_state.imagesRenderWidth = 961;
	CHECK_FALSE(images_match_configuration());
	g_state.imagesRenderWidth = 960;

	g_state.motionImage.view = VK_NULL_HANDLE;
	CHECK_FALSE(images_match_configuration());
	g_state.motionImage.view = fake_view(1);
	g_state.outputImage.view = VK_NULL_HANDLE;
	CHECK_FALSE(images_match_configuration());
}

TEST_CASE("wait_device_idle is a no-op on the null device") {
	reset_state();
	CHECK(g_state.device == VK_NULL_HANDLE);
	wait_device_idle();
}

TEST_CASE("record_layout_transition returns early when the layout is unchanged") {
	reset_state();
	const VkCommandBuffer garbage =
		reinterpret_cast<VkCommandBuffer>(std::uintptr_t{0xDEADBEEF});
	const VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
	record_layout_transition(garbage, VK_NULL_HANDLE, range, VK_IMAGE_LAYOUT_GENERAL,
							 VK_IMAGE_LAYOUT_GENERAL);
	CHECK(g_state.motionImage.layout == VK_IMAGE_LAYOUT_UNDEFINED);
	CHECK(g_state.outputImage.layout == VK_IMAGE_LAYOUT_UNDEFINED);
}

TEST_CASE("current_layout_of and note_layout_after_transition track known images") {
	reset_state();
	g_state.motionImage.image = reinterpret_cast<VkImage>(std::uintptr_t{0x1234});
	g_state.outputImage.image = reinterpret_cast<VkImage>(std::uintptr_t{0x5678});
	g_state.rrOutputImage.image = reinterpret_cast<VkImage>(std::uintptr_t{0x9ABC});

	CHECK(current_layout_of(0x1234) == VK_IMAGE_LAYOUT_UNDEFINED);
	CHECK(current_layout_of(0x5678) == VK_IMAGE_LAYOUT_UNDEFINED);
	CHECK(current_layout_of(0x9ABC) == VK_IMAGE_LAYOUT_UNDEFINED);

	note_layout_after_transition(0x1234, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
	CHECK(current_layout_of(0x1234) == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
	note_layout_after_transition(0x5678, VK_IMAGE_LAYOUT_GENERAL);
	CHECK(current_layout_of(0x5678) == VK_IMAGE_LAYOUT_GENERAL);
	note_layout_after_transition(0x9ABC, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
	CHECK(current_layout_of(0x9ABC) == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

	CHECK(current_layout_of(0xFFFF) == kEngineRestingLayout);
	note_layout_after_transition(0xFFFF, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
	CHECK(current_layout_of(0xFFFF) == kEngineRestingLayout);
	CHECK(current_layout_of(0x1234) == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
}

TEST_CASE("record_batched_transitions early-returns on null buffer, null desc, or zero count") {
	reset_state();
	const VkCommandBuffer garbage =
		reinterpret_cast<VkCommandBuffer>(std::uintptr_t{0xDEADBEEF});
	ImageTransitionDesc desc{};
	desc.image = reinterpret_cast<VkImage>(std::uintptr_t{0x1234});
	desc.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
	desc.newLayout = VK_IMAGE_LAYOUT_GENERAL;

	record_batched_transitions(VK_NULL_HANDLE, &desc, 1);
	record_batched_transitions(garbage, nullptr, 1);
	record_batched_transitions(garbage, &desc, 0);
	CHECK(g_state.motionImage.layout == VK_IMAGE_LAYOUT_UNDEFINED);
}

TEST_CASE("record_batched_transitions filters true no-ops when layout, access, and stages are unchanged/zero") {
	reset_state();
	const VkCommandBuffer garbage =
		reinterpret_cast<VkCommandBuffer>(std::uintptr_t{0xDEADBEEF});
	const VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};

	ImageTransitionDesc noopDesc{};
	noopDesc.image = reinterpret_cast<VkImage>(std::uintptr_t{0x1234});
	noopDesc.subresourceRange = range;
	noopDesc.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
	noopDesc.newLayout = VK_IMAGE_LAYOUT_GENERAL;
	noopDesc.srcStage = 0;
	noopDesc.dstStage = 0;
	noopDesc.srcAccess = 0;
	noopDesc.dstAccess = 0;

	record_batched_transitions(garbage, &noopDesc, 1);

	ImageTransitionDesc nullImgDesc{};
	nullImgDesc.image = VK_NULL_HANDLE;
	nullImgDesc.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
	nullImgDesc.newLayout = VK_IMAGE_LAYOUT_GENERAL;
	nullImgDesc.srcStage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
	nullImgDesc.dstStage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;

	record_batched_transitions(garbage, &nullImgDesc, 1);
	CHECK(g_state.motionImage.layout == VK_IMAGE_LAYOUT_UNDEFINED);
}
