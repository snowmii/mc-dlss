#include "internal/common.h"

#include "doctest.h"

#include <string>

using namespace mc_dlss;

TEST_CASE("utf8_to_wide converts valid UTF-8 on the Windows MultiByteToWideChar path") {
	std::wstring wide;
	CHECK(utf8_to_wide("motion", wide));
	CHECK(wide == L"motion");

	CHECK(utf8_to_wide("h\xC3\xA9llo", wide));
	CHECK(wide == L"h\u00E9llo");

	CHECK(utf8_to_wide("x\xF0\x9F\x98\x80y", wide));
	CHECK(wide.size() == 4);
	CHECK(wide[0] == L'x');
	CHECK(wide[1] == 0xD83D);
	CHECK(wide[2] == 0xDE00);
	CHECK(wide[3] == L'y');
}

TEST_CASE("utf8_to_wide refuses empty, null, and malformed input") {
	std::wstring wide = L"leftover";
	CHECK_FALSE(utf8_to_wide("", wide));
	CHECK_FALSE(utf8_to_wide(nullptr, wide));
	CHECK_FALSE(utf8_to_wide("\xC3", wide));
	CHECK_FALSE(utf8_to_wide("ok\xC3", wide));
}

TEST_CASE("valid_dimensions requires all four dimensions and render <= output") {
	CHECK(valid_dimensions(1920, 1080, 1920, 1080));
	CHECK(valid_dimensions(1920, 1080, 960, 540));
	CHECK_FALSE(valid_dimensions(0, 1080, 960, 540));
	CHECK_FALSE(valid_dimensions(1920, 0, 960, 540));
	CHECK_FALSE(valid_dimensions(1920, 1080, 0, 540));
	CHECK_FALSE(valid_dimensions(1920, 1080, 960, 0));
	CHECK_FALSE(valid_dimensions(1920, 1080, 1921, 540));
	CHECK_FALSE(valid_dimensions(1920, 1080, 960, 1081));
}

TEST_CASE("valid_image requires view, image, and a defined format") {
	McDlssImage image{};
	image.view = 1;
	image.image = 2;
	image.format = static_cast<uint32_t>(VK_FORMAT_R16G16_SFLOAT);
	CHECK(valid_image(image));

	image.view = 0;
	CHECK_FALSE(valid_image(image));
	image.view = 1;
	image.image = 0;
	CHECK_FALSE(valid_image(image));
	image.image = 2;
	image.format = static_cast<uint32_t>(VK_FORMAT_UNDEFINED);
	CHECK_FALSE(valid_image(image));
}

TEST_CASE("image_range_of derives aspect and the constant {0,1,0,1} range") {
	const VkImageSubresourceRange depth = image_range_of(true);
	CHECK(depth.aspectMask == VK_IMAGE_ASPECT_DEPTH_BIT);
	CHECK(depth.baseMipLevel == 0);
	CHECK(depth.levelCount == 1);
	CHECK(depth.baseArrayLayer == 0);
	CHECK(depth.layerCount == 1);

	const VkImageSubresourceRange color = image_range_of(false);
	CHECK(color.aspectMask == VK_IMAGE_ASPECT_COLOR_BIT);
	CHECK(color.baseMipLevel == 0);
	CHECK(color.levelCount == 1);
	CHECK(color.baseArrayLayer == 0);
	CHECK(color.layerCount == 1);
}

TEST_CASE("virtual RR depth uses a color aspect while raster depth keeps its depth aspect") {
    McDlssImage image{};
    image.format = VK_FORMAT_D32_SFLOAT;
    CHECK(depth_range_of(image).aspectMask == VK_IMAGE_ASPECT_DEPTH_BIT);
    image.format = VK_FORMAT_R32_SFLOAT;
    CHECK(depth_range_of(image).aspectMask == VK_IMAGE_ASPECT_COLOR_BIT);
    CHECK(depth_range_of(image).levelCount == 1);
}
