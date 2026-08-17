// motion.cpp's device-free dispatch math: the render size rounds up to whole workgroups.
//
// doctest v2.4.11 (MIT) vendored as native/test/doctest.h
// https://github.com/doctest/doctest/releases/tag/v2.4.11
// https://raw.githubusercontent.com/doctest/doctest/v2.4.11/doctest/doctest.h
#include "internal/motion.h"

#include "doctest.h"

#include <cstdint>

using namespace mc_dlss;

TEST_CASE("motion_workgroup_count keeps whole workgroups on exact multiples") {
	CHECK(motion_workgroup_count(0) == 0);
	CHECK(motion_workgroup_count(8) == 1);
	CHECK(motion_workgroup_count(16) == 2);
	CHECK(motion_workgroup_count(64) == 8);
	CHECK(motion_workgroup_count(1920) == 240);
	CHECK(motion_workgroup_count(1080) == 135);
}

TEST_CASE("motion_workgroup_count rounds partial workgroups up") {
	CHECK(motion_workgroup_count(1) == 1);
	CHECK(motion_workgroup_count(7) == 1);
	CHECK(motion_workgroup_count(9) == 2);
	CHECK(motion_workgroup_count(15) == 2);
	CHECK(motion_workgroup_count(17) == 3);
	// One pixel either side of a group boundary at a realistic render width.
	CHECK(motion_workgroup_count(1919) == 240);
	CHECK(motion_workgroup_count(1921) == 241);
}

TEST_CASE("motion_workgroup_count matches the shaders' workgroup size") {
	// Both .comp shaders declare local_size_x/y = 8; the constant is the host-side copy of
	// that number, and the shaders' own bounds checks are what keep a drift harmless.
	CHECK(kMotionWorkgroupSize == 8);
	CHECK(motion_workgroup_count(kMotionWorkgroupSize) == 1);
}

TEST_CASE("motion_workgroup_count pins the unsigned wrap ceiling") {
	// (extent + kMotionWorkgroupSize - 1) wraps at UINT32_MAX, so the largest few extents
	// dead-reckon to 0 groups. No render extent can get there; the pins keep the wrap from
	// being silently "fixed" into a different dispatch count.
	CHECK(motion_workgroup_count(UINT32_MAX) == 0);
	CHECK(motion_workgroup_count(UINT32_MAX - 7) == 536870911);
}
