#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:texture_sampling.glsl>
#include <minecraft:oit.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
    #include <minecraft:chunksection.glsl>
#endif

uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 4) in float chunkVisibility;
#ifdef LUMINA_NORMALS
layout(location = 8) in vec3 luminaViewPosition;
#endif

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#ifdef LUMINA_NORMALS
layout(location = 1) out vec4 luminaNormal;
#endif
#endif

vec4 calculateFinalColor(vec4 color) {
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif
    return apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
}

void main() {
    // Skip vanilla RGSS only. sampleNearest is atlas-safe reconstruction (also the None/AF
    // path); feeding 4-tap RGSS into DLSS stacks reconstructors and smears on camera motion.
    vec4 color = sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize) * vertexColor;
    #ifndef OIT_ALPHA_ONLY
    color = mix(FogColor * vec4(1, 1, 1, color.a), color, chunkVisibility);
    #endif
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

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color);
    #ifdef LUMINA_NORMALS
    vec3 normal = normalize(cross(dFdx(luminaViewPosition), dFdy(luminaViewPosition)));
    luminaNormal = vec4(gl_FrontFacing ? normal : -normal, 1.0);
    #endif
    #endif
}
