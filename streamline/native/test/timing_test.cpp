// timing.cpp's no-pool, no-device no-op paths.
//
// doctest v2.4.11 (MIT) vendored as native/test/doctest.h
// https://github.com/doctest/doctest/releases/tag/v2.4.11
// https://raw.githubusercontent.com/doctest/doctest/v2.4.11/doctest/doctest.h
//
// The harness runs with no device and no query pool, so only the paths that must no-op
// without either are reachable; every case starts from a clean g_state/g_timing because
// the binary runs all cases in one process.
#include "internal/timing.h"

#include "doctest.h"

#include <cstdint>

using namespace mc_dlss;

namespace {

VkDevice fake_device() {
	return reinterpret_cast<VkDevice>(std::uintptr_t{1});
}

} // namespace

TEST_CASE("begin_frame_timing no-ops without a device") {
	reset_state();
	g_timing = DlssFrameTiming{};
	begin_frame_timing(VK_NULL_HANDLE);
	// The no-op must not silently disable timing: supported stays, nothing is written.
	CHECK(g_timing.supported);
	CHECK(g_timing.pool == VK_NULL_HANDLE);
	CHECK(g_timing.writtenStamps == 0);
	CHECK(g_timing.nextSlot == 0);
	CHECK(g_timing.recordingSlot == 0);
	for (uint32_t i = 0; i < kTimingSlotCount; ++i) {
		CHECK_FALSE(g_timing.pending[i]);
	}
}

TEST_CASE("begin_frame_timing no-ops before the device tuple is complete") {
	reset_state();
	g_timing = DlssFrameTiming{};
	// A device without its physical device cannot probe timestamp support, so the pool is
	// never created and the frame is not opened - and no driver call is made.
	g_state.device = fake_device();
	begin_frame_timing(VK_NULL_HANDLE);
	CHECK(g_timing.supported);
	CHECK(g_timing.pool == VK_NULL_HANDLE);
	CHECK(g_timing.writtenStamps == 0);
	CHECK(g_timing.nextSlot == 0);
}

TEST_CASE("mark_frame_timing no-ops without a pool") {
	reset_state();
	g_timing = DlssFrameTiming{};
	mark_frame_timing(VK_NULL_HANDLE, 0);
	CHECK(g_timing.writtenStamps == 0);
	// A stage index that does not match the recording slot's progress is refused too.
	mark_frame_timing(VK_NULL_HANDLE, 3);
	CHECK(g_timing.writtenStamps == 0);
}

TEST_CASE("destroy_timing returns g_timing to its defaults") {
	reset_state();
	g_timing = DlssFrameTiming{};
	g_timing.supported = false;
	g_timing.recordingSlot = 2;
	g_timing.nextSlot = 3;
	g_timing.writtenStamps = 4;
	g_timing.hasResult = true;
	g_timing.motionMs = 5.0f;
	g_timing.evaluateMs = 6.0f;

	destroy_timing();

	const DlssFrameTiming defaults{};
	CHECK(g_timing.supported == defaults.supported);
	CHECK(g_timing.pool == VK_NULL_HANDLE);
	CHECK(g_timing.timestampPeriod == defaults.timestampPeriod);
	CHECK(g_timing.recordingSlot == 0);
	CHECK(g_timing.nextSlot == 0);
	CHECK(g_timing.writtenStamps == 0);
	CHECK_FALSE(g_timing.hasResult);
	CHECK(g_timing.motionMs == 0.0f);
	CHECK(g_timing.evaluateMs == 0.0f);
	for (uint32_t i = 0; i < kTimingSlotCount; ++i) {
		CHECK_FALSE(g_timing.pending[i]);
	}
}
