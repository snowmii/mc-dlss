#version 330

// Terrain velocity fragment shader: the vanilla core/terrain fragment shader plus the
// velocity-MRT payload write.
//
// The terrain velocity twin swaps this shader in for the source's core/terrain fragment
// shader, so the color output must be byte-identical to vanilla's. The body below is the
// vanilla terrain.fsh verbatim (RGSS/nearest sampling, vertex color, fog, ALPHA_CUTOUT
// discard); the additions are the VelocityConfig uniform block, the velocityColor output,
// and the per-pixel camera-motion computation at the end of main().
//
// The motion is the same jitter-stripped NDC camera vector the mod-owned stress pass writes:
// the reprojection maps this pixel's jittered clip position - what the rendered frame and its
// reversed-Z depth actually hold - to where that same surface sat in the previous frame, and
// subtracting the current NDC leaves the motion vector. A still camera therefore reads zero at
// every depth whatever the jitter moved, because the reprojection collapses to the identity.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:chunksection.glsl>

uniform sampler2D Sampler0;

// Jitter-stripped current-to-previous clip reprojection, exactly as this frame's DLSS
// evaluation receives it, plus the per-frame classification inputs.
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
// The terrain velocity twin's second color target: jitter-free NDC camera motion. This shader
// is only ever bound on the velocity twin, whose second attachment this output writes; on the
// one-target vanilla pipeline the vanilla terrain shader is bound instead.
out vec4 velocityColor;

// The single representable sentinel for pixels with no valid motion: far outside the [-1, 1]
// NDC range a real camera-motion vector can reach, and exactly representable in the RG16_FLOAT
// payload. A frame with no predecessor, a pixel whose reprojection the previous camera cannot
// see, and any pixel whose derived vector is non-finite or out of range must all write this
// rather than the identity-derived zero (which would read as a camera that stood still) or an
// Inf/NaN payload value. The velocity attachment is also cleared to this value before the
// opaque terrain group draws, so pixels the terrain never writes - sky, discarded cutout
// texels, the far plane - read invalid rather than stale motion.
const float INVALID_VELOCITY = 10000.0;

vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv, vec2 texelScreenSize) {
    // Convert our UV back up to texel coordinates and find out how far over we are from the center of each pixel
    vec2 uvTexelCoords = uv / pixelSize;
    vec2 texelCenter = round(uvTexelCoords) - 0.5f;
    vec2 texelOffset = uvTexelCoords - texelCenter;

    // Move our offset closer to the texel center based on texel size on screen
    texelOffset = (texelOffset - 0.5f) * pixelSize / texelScreenSize + 0.5f;
    texelOffset = clamp(texelOffset, 0.0f, 1.0f);

    uv = (texelCenter + texelOffset) * pixelSize;
    return textureGrad(source, uv, du, dv);
}

vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    return sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
}

// Rotated Grid Super-Sampling
vec4 sampleRGSS(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);

    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);

    float minPixelSize = min(pixelSize.x, pixelSize.y);

    float transitionStart = minPixelSize * 1.0;
    float transitionEnd = minPixelSize * 2.0;
    float blendFactor = smoothstep(transitionStart, transitionEnd, maxTexelSize);

    float duLength = length(du);
    float dvLength = length(dv);
    float minDerivative = min(duLength, dvLength);
    float maxDerivative = max(duLength, dvLength);

    float effectiveDerivative = sqrt(minDerivative * maxDerivative);

    float mipLevelExact = max(0.0, log2(effectiveDerivative / minPixelSize));

    float mipLevelLow = floor(mipLevelExact);
    float mipLevelHigh = mipLevelLow + 1.0;
    float mipBlend = fract(mipLevelExact);

    const vec2 offsets[4] = vec2[](
    vec2(0.125, 0.375),
    vec2(-0.125, -0.375),
    vec2(0.375, -0.125),
    vec2(-0.375, 0.125)
    );

    vec4 rgssColorLow = vec4(0.0);
    vec4 rgssColorHigh = vec4(0.0);
    for (int i = 0; i < 4; ++i) {
        vec2 sampleUV = uv + offsets[i] * pixelSize;
        rgssColorLow += textureLod(source, sampleUV, mipLevelLow);
        rgssColorHigh += textureLod(source, sampleUV, mipLevelHigh);
    }
    rgssColorLow *= 0.25;
    rgssColorHigh *= 0.25;

    vec4 rgssColor = mix(rgssColorLow, rgssColorHigh, mipBlend);

    vec4 nearestColor = sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);

    return mix(nearestColor, rgssColor, blendFactor);
}

void main() {
    vec4 color = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize) : sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize)) * vertexColor;
    color = mix(FogColor * vec4(1, 1, 1, color.a), color, ChunkVisibility);
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);

    // Jitter-free NDC camera motion, the payload DLSS reads for the terrain this fragment drew.
    //
    // The fragment's own clip position is reconstructed exactly: gl_FragCoord is the
    // window-space position of this fragment inside the velocity attachment, and inverting the
    // backend's fixed viewport transform (origin top-left, NDC y down, no flip) recovers the
    // normalized device coordinates the vertex shader produced. gl_FragCoord.z is the
    // reversed-Z depth - 1.0 the near plane, 0.0 the far plane - which the backend maps to
    // clip.z / clip.w directly, so it goes straight into clip.z: the same convention the
    // reprojection was composed from, and the same `vec4(ndc, depth, 1.0)` the stress pass
    // feeds its sampled depth. Flipping or remapping either would point previous-frame
    // positions at the wrong surface.
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
