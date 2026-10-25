// Native output-format contract, exercised without a Vulkan device.
#include "internal/common.h"

#include "doctest.h"

using namespace mc_dlss;

TEST_CASE("SR keeps RGBA8 while RR uses an HDR RGBA16F output") {
    CHECK(kOutputFormat == VK_FORMAT_R8G8B8A8_UNORM);
    CHECK(kRrOutputFormat == VK_FORMAT_R16G16B16A16_SFLOAT);
}
