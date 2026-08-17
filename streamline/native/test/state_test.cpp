// DlssState reset and the module-owned image/layout seams, exercised device-free.
//
// doctest v2.4.11 (MIT) vendored as native/test/doctest.h
// https://github.com/doctest/doctest/releases/tag/v2.4.11
// https://raw.githubusercontent.com/doctest/doctest/v2.4.11/doctest/doctest.h
//
// None of these seams touches a real Vulkan object: the fake handles below are plain
// non-null pointers or integers, and the one call that could reach the driver
// (record_layout_transition) is exercised only on its equal-layout early return.
#include "internal/state.h"

#include "doctest.h"

#include <cstdint>

using namespace mc_dlss;

namespace {

VkImageView fake_view(const std::uintptr_t value) {
	return reinterpret_cast<VkImageView>(value);
}

} // namespace

TEST_CASE("reset_state returns every recorded field to its default") {
	reset_state();
	// Dirty a spread of fields across the struct's regions.
	g_state.sessionReady = true;
	g_state.streamlineInitialized = true;
	g_state.deviceValue = 0x1234;
	g_state.proxyComputeQueueIndex = 3;
	g_state.outputWidth = 1920;
	g_state.outputHeight = 1080;
	g_state.renderWidth = 960;
	g_state.renderHeight = 540;
	g_state.qualityMode = 2;
	g_state.fgNumBackBuffers = 3;
	g_state.fgNumFramesToGenerate = 3;
	g_state.reflexOptionsRecorded = true;
	g_state.reflexMode = 1;
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
	g_state.frameToken = reinterpret_cast<sl::FrameToken*>(std::uintptr_t{1});
	g_state.presentTokenArmed = true;
	g_state.presentStartEmitted = true;
	g_state.lastSrTagFrameIndex = 9;
	g_state.fgCopiedFrameIndex = 11;
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
	CHECK(g_state.fgNumBackBuffers == defaults.fgNumBackBuffers);
	// The FG multiplier resets to the 2x default, not to 0.
	CHECK(g_state.fgNumFramesToGenerate == 1);
	CHECK(g_state.reflexOptionsRecorded == defaults.reflexOptionsRecorded);
	CHECK(g_state.reflexMode == defaults.reflexMode);
	CHECK(g_state.reflexSetOptionsCalls == defaults.reflexSetOptionsCalls);
	CHECK(g_state.reflexFrameLimitUs == defaults.reflexFrameLimitUs);
	CHECK(g_state.motionImage.image == VK_NULL_HANDLE);
	CHECK(g_state.motionImage.view == VK_NULL_HANDLE);
	// Fresh allocations rest at VK_IMAGE_LAYOUT_UNDEFINED.
	CHECK(g_state.motionImage.layout == VK_IMAGE_LAYOUT_UNDEFINED);
	CHECK(g_state.outputImage.layout == VK_IMAGE_LAYOUT_UNDEFINED);
	CHECK(g_state.imagesOutputWidth == defaults.imagesOutputWidth);
	CHECK(g_state.presentMarkers.eventCount == 0);
	CHECK(g_state.presentMarkers.typeCounts[mc_dlss::kPresentMarkerStart] == 0);
	CHECK(g_state.reflexMarkers.eventCount == 0);
	CHECK(g_state.reflexMarkers.typeCounts[mc_dlss::kReflexMarkerInputSample] == 0);
	CHECK(g_state.frameToken == nullptr);
	CHECK(g_state.presentTokenArmed == defaults.presentTokenArmed);
	CHECK(g_state.presentStartEmitted == defaults.presentStartEmitted);
	CHECK(g_state.lastSrTagFrameIndex == defaults.lastSrTagFrameIndex);
	CHECK(g_state.fgCopiedFrameIndex == defaults.fgCopiedFrameIndex);
	CHECK(g_state.cameraConstantsRecorded == defaults.cameraConstantsRecorded);
	CHECK(g_state.lastCameraConstants.pos[0] == defaults.lastCameraConstants.pos[0]);
}

TEST_CASE("images_match_configuration requires views and matching dimensions") {
	reset_state();
	g_state.motionImage.view = fake_view(1);
	g_state.outputImage.view = fake_view(2);
	g_state.imagesRenderWidth = g_state.renderWidth = 960;
	g_state.imagesRenderHeight = g_state.renderHeight = 540;
	g_state.imagesOutputWidth = g_state.outputWidth = 1920;
	g_state.imagesOutputHeight = g_state.outputHeight = 1080;
	CHECK(images_match_configuration());

	// One dimension out of agreement is a mismatch.
	g_state.imagesOutputHeight = 1079;
	CHECK_FALSE(images_match_configuration());
	g_state.imagesOutputHeight = 1080;
	g_state.imagesRenderWidth = 961;
	CHECK_FALSE(images_match_configuration());
	g_state.imagesRenderWidth = 960;

	// A missing view means no configuration even with equal dimensions.
	g_state.motionImage.view = VK_NULL_HANDLE;
	CHECK_FALSE(images_match_configuration());
	g_state.motionImage.view = fake_view(1);
	g_state.outputImage.view = VK_NULL_HANDLE;
	CHECK_FALSE(images_match_configuration());
}

TEST_CASE("wait_device_idle is a no-op on the null device") {
	reset_state();
	CHECK(g_state.device == VK_NULL_HANDLE);
	// Must not dereference anything or reach the loader; survives because the device is null.
	wait_device_idle();
}

TEST_CASE("record_layout_transition returns early when the layout is unchanged") {
	reset_state();
	// The command buffer handle is garbage on purpose: the equal-layout path returns before
	// touching it, so a barrier is never recorded and the driver is never reached.
	const VkCommandBuffer garbage =
		reinterpret_cast<VkCommandBuffer>(std::uintptr_t{0xDEADBEEF});
	const VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
	record_layout_transition(garbage, VK_NULL_HANDLE, range, VK_IMAGE_LAYOUT_GENERAL,
							 VK_IMAGE_LAYOUT_GENERAL);
	// The transition records no state of its own; a successful early return changes nothing.
	CHECK(g_state.motionImage.layout == VK_IMAGE_LAYOUT_UNDEFINED);
	CHECK(g_state.outputImage.layout == VK_IMAGE_LAYOUT_UNDEFINED);
}

TEST_CASE("current_layout_of and note_layout_after_transition track known images") {
	reset_state();
	g_state.motionImage.image = reinterpret_cast<VkImage>(std::uintptr_t{0x1234});
	g_state.outputImage.image = reinterpret_cast<VkImage>(std::uintptr_t{0x5678});

	// Fresh allocations rest at UNDEFINED until the first transition notes a layout.
	CHECK(current_layout_of(0x1234) == VK_IMAGE_LAYOUT_UNDEFINED);
	CHECK(current_layout_of(0x5678) == VK_IMAGE_LAYOUT_UNDEFINED);

	note_layout_after_transition(0x1234, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
	CHECK(current_layout_of(0x1234) == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
	note_layout_after_transition(0x5678, VK_IMAGE_LAYOUT_GENERAL);
	CHECK(current_layout_of(0x5678) == VK_IMAGE_LAYOUT_GENERAL);

	// Anything else came from the engine and rests where Minecraft leaves its textures.
	CHECK(current_layout_of(0xFFFF) == kEngineRestingLayout);
	// Noting a layout for an unknown image changes nothing.
	note_layout_after_transition(0xFFFF, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
	CHECK(current_layout_of(0xFFFF) == kEngineRestingLayout);
	CHECK(current_layout_of(0x1234) == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
}
