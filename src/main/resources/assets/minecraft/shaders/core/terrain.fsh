#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:chunksection.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

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

void main() {
    // Skip vanilla RGSS only. sampleNearest is atlas-safe reconstruction (also the None/AF
    // path); feeding 4-tap RGSS into DLSS stacks reconstructors and smears on camera motion.
    vec4 color = sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize) * vertexColor;
    color = mix(FogColor * vec4(1, 1, 1, color.a), color, ChunkVisibility);
#ifdef ALPHA_CUTOUT
    // Magnified cutouts: discard at the atlas texel centre so a 0.5px Halton step cannot flip
    // fancy-leaf holes. Minified (Perf/Ultra, distant): that lod-0 snap is one texel vs a pixel
    // that covers many, which sparkles — use the filtered colour alpha instead.
    vec2 atlasTexel = 1.0 / vec2(TextureSize);
    vec2 uvFootprint = abs(dFdx(texCoord0)) + abs(dFdy(texCoord0));
    if (max(uvFootprint.x, uvFootprint.y) < min(atlasTexel.x, atlasTexel.y)) {
        vec2 cutoutUv = (floor(texCoord0 / atlasTexel) + 0.5) * atlasTexel;
        if (textureLod(Sampler0, cutoutUv, 0.0).a * vertexColor.a < ALPHA_CUTOUT) {
            discard;
        }
    } else if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
