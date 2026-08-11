#version 330

// Breaking-block crumbling velocity fragment shader: the vanilla core/rendertype_crumbling
// fragment shader - which is what the CRUMBLING pipeline binds - plus the velocity-MRT
// payload write.
//
// The crumbling velocity twin swaps this shader in for the source's core/rendertype_crumbling
// fragment shader, so the color output must be byte-identical to vanilla's. The body below is
// the vanilla rendertype_crumbling.fsh verbatim (Sampler0 sampling, vertex color, the < 0.1
// alpha discard, the ColorModulator multiply after the discard, and apply_fog); the additions
// are the VelocityConfig uniform block, the velocityColor output, and the per-pixel
// camera-motion computation at the end of main().
//
// The fog and dynamic-transform uniform blocks are inlined from the vanilla includes
// (fog.glsl, dynamictransforms.glsl) instead of #moj_import because this shader must compile
// through the plain Shaderc + spirv-cross path the headless suite uses to pin the output
// order; the block names, member names, types, and order are exactly the includes', so the
// bind-group layouts the source pipeline already carries resolve them the same way.

layout(std140) uniform Fog {
    vec4 FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
};

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0;

// Jitter-stripped current-to-previous clip reprojection, exactly as this frame's DLSS
// evaluation receives it, plus the per-frame classification inputs. The block is the terrain
// writer's existing VelocityConfig payload: the crumbling writer fills the same block on the
// draw's own command encoder, so the breaking overlay reads the same camera motion and reset
// semantics the terrain pass does.
layout(std140) uniform VelocityConfig {
    mat4 Reprojection;
    // x: 1.0 when this frame has no valid predecessor, so every pixel writes the invalid
    //    sentinel instead of the identity-derived zero.
    // yz: the velocity viewport size in pixels, which is what gl_FragCoord is sized to; the
    //    shader inverts the backend's fixed viewport transform with it to recover NDC.
    // w: unused.
    vec4 VelocityParams;
};

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;
// The crumbling velocity twin's second color target: jitter-free NDC camera motion. This
// shader is only ever bound on the velocity twin, whose second attachment this output writes;
// on the one-target vanilla crumbling pipeline the vanilla rendertype_crumbling shader is
// bound instead.
out vec4 velocityColor;

// The single representable sentinel for pixels with no valid motion: far outside the [-1, 1]
// NDC range a real camera-motion vector can reach, and exactly representable in the RG16_FLOAT
// payload. A frame with no predecessor, a pixel whose reprojection the previous camera cannot
// see, and any pixel whose derived vector is non-finite or out of range must all write this
// rather than the identity-derived zero (which would read as a camera that stood still) or an
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

float total_fog_value(float sphericalVertexDistance, float cylindricalVertexDistance, float environmentalStart, float environmantalEnd, float renderDistanceStart, float renderDistanceEnd) {
    return max(linear_fog_value(sphericalVertexDistance, environmentalStart, environmantalEnd), linear_fog_value(cylindricalVertexDistance, renderDistanceStart, renderDistanceEnd));
}

vec4 apply_fog(vec4 inColor, float sphericalVertexDistance, float cylindricalVertexDistance, float environmentalStart, float environmantalEnd, float renderDistanceStart, float renderDistanceEnd, vec4 fogColor) {
    float fogValue = total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, environmentalStart, environmantalEnd, renderDistanceStart, renderDistanceEnd);
    return vec4(mix(inColor.rgb, fogColor.rgb, fogValue * fogColor.a), inColor.a);
}

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
    if (color.a < 0.1) {
        discard;
    }
    color = color * ColorModulator;
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);

    // Jitter-free NDC camera motion, the payload DLSS reads for the breaking overlay this
    // fragment drew.
    //
    // The fragment's own clip position is reconstructed exactly: gl_FragCoord is the
    // window-space position of this fragment inside the velocity attachment, and inverting the
    // backend's fixed viewport transform (origin top-left, NDC y down, no flip) recovers the
    // normalized device coordinates the vertex shader produced. gl_FragCoord.z is the
    // reversed-Z depth - 1.0 the near plane, 0.0 the far plane - which the backend maps to
    // clip.z / clip.w directly, so it goes straight into clip.z: the same convention the
    // reprojection was composed from, and the same `vec4(ndc, depth, 1.0)` the terrain and
    // stress writers feed.
    vec2 ndc = vec2(gl_FragCoord.x / VelocityParams.y * 2.0 - 1.0, gl_FragCoord.y / VelocityParams.z * 2.0 - 1.0);
    vec4 clip = vec4(ndc, gl_FragCoord.z, 1.0);
    vec4 previous = Reprojection * clip;
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
        // camera motion spans a few NDC units, so the sentinel magnitude is a wide safety
        // margin, not a bound any valid vector approaches.
        if (motion.x != motion.x || motion.y != motion.y ||
            abs(motion.x) >= INVALID_VELOCITY || abs(motion.y) >= INVALID_VELOCITY) {
            invalidPixel = true;
        }
    }
    velocityColor = invalidPixel ? vec4(INVALID_VELOCITY, INVALID_VELOCITY, 0.0, 0.0) : vec4(motion, 0.0, 0.0);
}
