#version 330

// Cloud velocity fragment shader: the vanilla core/rendertype_clouds fragment shader - which
// is what the cloud pipelines CLOUDS and FLAT_CLOUDS bind - plus the velocity-MRT payload
// write.
//
// The cloud velocity twin swaps this shader in for the source's core/rendertype_clouds
// fragment shader, so the color output must be byte-identical to vanilla's. The body below is
// the vanilla rendertype_clouds.fsh verbatim (the vertex color with only the fog alpha fade -
// clouds sample no texture and blend no fog color); the additions are the CloudVelocityConfig
// uniform block, the velocityColor output, and the per-pixel object-motion computation at the
// end of main().
//
// The Fog uniform block is inlined from the vanilla fog.glsl include instead of #moj_import
// because this shader must compile through the plain Shaderc + spirv-cross path the headless
// suite uses to pin the output order; the block name, member names, types, and order are
// exactly the include's, so the bind-group layout the source pipeline already carries resolves
// it the same way.

layout(std140) uniform Fog {
    vec4 FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
};

// ObjectReprojection maps this frame's jittered clip position - what the rendered cloud
// surface and its reversed-Z depth actually hold - to where that same surface sat in the
// previous frame: the camera's jitter-stripped reprojection with the cloud pattern's own
// drift displacement (a constant -0.03 blocks per tick of the game clock along X) conjugated
// into it, exactly the composition the writer derives from the per-frame cloud-offset delta.
// VelocityParams.x is the reset/unknown-predecessor flag; yz is the scene velocity viewport
// size, which is what gl_FragCoord is sized to.
layout(std140) uniform CloudVelocityConfig {
    mat4 ObjectReprojection;
    // x: 1.0 when this frame has no valid predecessor, so every pixel writes the invalid
    //    sentinel instead of the identity-derived zero.
    // yz: the velocity viewport size in pixels; the shader inverts the backend's fixed
    //    viewport transform with it to recover NDC.
    // w: unused.
    vec4 VelocityParams;
};

in float vertexDistance;
in vec4 vertexColor;

out vec4 fragColor;
// The cloud velocity twin's second color target: NDC cloud motion. This shader is only ever
// bound on the velocity twin, whose second attachment this output writes; on the one-target
// vanilla cloud pipeline the vanilla rendertype_clouds shader is bound instead.
out vec4 velocityColor;

// The single representable sentinel for pixels with no valid motion: far outside the [-1, 1]
// NDC range a real motion vector can reach, and exactly representable in the RG16_FLOAT
// payload. A frame with no predecessor, a pixel whose reprojection the previous camera cannot
// see, and any pixel whose derived vector is non-finite or out of range must all write this
// rather than the identity-derived zero (which would read as clouds that stood still) or an
// Inf/NaN payload value.
const float INVALID_VELOCITY = 10000.0;

float linear_fog_value(float vertexDistance, float fogStart, float fogEnd) {
    if (vertexDistance <= fogStart) {
        return 0.0;
    } else if (vertexDistance >= fogEnd) {
        return 1.0;
    }

    return (vertexDistance - fogStart) / (fogEnd - fogStart);
}

void main() {
    vec4 color = vertexColor;
    color.a *= 1.0f - linear_fog_value(vertexDistance, 0, FogCloudsEnd);
    fragColor = color;

    // NDC cloud motion, the payload DLSS reads for the cloud this fragment drew.
    //
    // The fragment's own clip position is reconstructed exactly: gl_FragCoord is the
    // window-space position of this fragment inside the velocity attachment, and inverting the
    // backend's fixed viewport transform (origin top-left, NDC y down, no flip) recovers the
    // normalized device coordinates the vertex shader produced. gl_FragCoord.z is the
    // reversed-Z depth - 1.0 the near plane, 0.0 the far plane - which the backend maps to
    // clip.z / clip.w directly, so it goes straight into clip.z: the same convention the
    // reprojection was composed from, and the same `vec4(ndc, depth, 1.0)` the terrain and
    // entity writers feed.
    vec2 ndc = vec2(gl_FragCoord.x / VelocityParams.y * 2.0 - 1.0, gl_FragCoord.y / VelocityParams.z * 2.0 - 1.0);
    vec4 clip = vec4(ndc, gl_FragCoord.z, 1.0);
    vec4 previous = ObjectReprojection * clip;
    // Per-pixel invalid classification, before the divide: a previous w that is zero (the
    // previous camera's eye plane), negative (behind the previous camera - dividing would
    // mirror the point into a plausible-looking but wrong NDC), or non-finite (NaN/Inf) is a
    // homogeneous coordinate no camera ever projected, and must never produce a finite motion.
    // The reset flag - a frame with no valid predecessor - forces every pixel invalid on top of
    // that, so a reset never reads as the identity-derived zero.
    bool invalidPixel = false;
    if (VelocityParams.x > 0.5 || previous.w <= 0.0 || previous.w != previous.w || isinf(previous.w)) {
        invalidPixel = true;
    }
    vec2 motion = vec2(0.0);
    if (!invalidPixel) {
        motion = previous.xy / previous.w - ndc;
        // A non-finite or out-of-range result must never reach the RG16_FLOAT payload. NaN is
        // the one value that compares unequal to itself; Infinity and any magnitude at or
        // beyond the sentinel itself - which would either overflow the half-float payload or
        // collide with the sentinel - all collapse to the same invalid classification. Real
        // cloud motion spans a few NDC units, so the sentinel magnitude is a wide safety
        // margin, not a bound any valid vector approaches.
        if (motion.x != motion.x || motion.y != motion.y ||
            abs(motion.x) >= INVALID_VELOCITY || abs(motion.y) >= INVALID_VELOCITY) {
            invalidPixel = true;
        }
    }
    velocityColor = invalidPixel ? vec4(INVALID_VELOCITY, INVALID_VELOCITY, 0.0, 0.0) : vec4(motion, 0.0, 0.0);
}
