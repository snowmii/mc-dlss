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
	CHECK(motion_workgroup_count(1919) == 240);
	CHECK(motion_workgroup_count(1921) == 241);
}

TEST_CASE("motion_workgroup_count matches the shaders' workgroup size") {
	CHECK(kMotionWorkgroupSize == 8);
	CHECK(motion_workgroup_count(kMotionWorkgroupSize) == 1);
}

TEST_CASE("motion_workgroup_count pins the unsigned wrap ceiling") {
	CHECK(motion_workgroup_count(UINT32_MAX) == 0);
	CHECK(motion_workgroup_count(UINT32_MAX - 7) == 536870911);
}
