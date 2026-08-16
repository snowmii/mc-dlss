#include "internal/common.h"

#include <cstring>
#include <limits>

#if defined(_WIN32)
#include <windows.h>
#else
#include <codecvt>
#include <locale>
#endif

namespace mc_dlss {

bool utf8_to_wide(const char* input, std::wstring& output) noexcept {
    if (input == nullptr || input[0] == '\0') {
        return false;
    }
#if defined(_WIN32)
    const size_t size = std::strlen(input);
    if (size > static_cast<size_t>(std::numeric_limits<int>::max())) {
        return false;
    }
    const int required = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, input,
                                              static_cast<int>(size), nullptr, 0);
    if (required <= 0) {
        return false;
    }
    output.resize(static_cast<size_t>(required));
    return MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, input,
                               static_cast<int>(size), output.data(), required) == required;
#else
    try {
        output = std::wstring_convert<std::codecvt_utf8<wchar_t>>().from_bytes(input);
        return !output.empty();
    } catch (...) {
        return false;
    }
#endif
}

bool valid_dimensions(const uint32_t outputWidth, const uint32_t outputHeight,
                      const uint32_t renderWidth, const uint32_t renderHeight) noexcept {
    return outputWidth != 0 && outputHeight != 0 && renderWidth != 0 && renderHeight != 0 &&
           renderWidth <= outputWidth && renderHeight <= outputHeight;
}

bool valid_image(const McDlssImage& image) noexcept {
    return image.view != 0 && image.image != 0 &&
           image.format != static_cast<uint32_t>(VK_FORMAT_UNDEFINED);
}

VkImageSubresourceRange image_range_of(const bool isDepth) noexcept {
    const VkImageAspectFlags aspect =
        isDepth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT;
    return VkImageSubresourceRange{aspect, 0, 1, 0, 1};
}

} // namespace mc_dlss
