#version 330

// Hand-map velocity twin. Vanilla world-text color logic stays byte-for-byte in fragColor,
// including the IS_GRAYSCALE and IS_SEE_THROUGH defines the core/text family carries;
// velocityColor is written only by the second RG16_FLOAT MRT target.

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#moj_import <minecraft:fog.glsl>
#endif

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
in float sphericalVertexDistance;
in float cylindricalVertexDistance;
#endif

in vec4 vertexColor;
in vec2 texCoord0;

// ObjectReprojection maps this frame's clip position to the hand's previous-frame clip
// position. VelocityParams.x is the reset/invalid flag; yz is the scene velocity viewport.
layout(std140) uniform HandVelocityConfig {
    mat4 ObjectReprojection;
    vec4 VelocityParams;
};

// Keep output declaration order: target 0 scene color, target 1 motion payload.
out vec4 fragColor;
out vec4 velocityColor;

const float INVALID_VELOCITY = 10000.0;

void main() {
#ifdef IS_GRAYSCALE
    vec4 texColor = texture(Sampler0, texCoord0).rrrr;
#else
    vec4 texColor = texture(Sampler0, texCoord0);
#endif

#ifdef IS_SEE_THROUGH
    vec4 color = texColor * vertexColor;
#else
    vec4 color = texColor * vertexColor * ColorModulator;
#endif
    if (color.a < 0.1) {
        discard;
    }

#ifdef IS_SEE_THROUGH
    fragColor = color * ColorModulator;
#elif defined(IS_GUI)
    fragColor = color;
#else
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
#endif

    // Reconstruct this hand fragment's current clip position from its window coordinate and
    // reversed-Z depth. The previous homogeneous position is classified before division so an
    // eye-plane/behind-camera/non-finite predecessor cannot become a plausible mirrored vector.
    vec2 ndc = vec2(gl_FragCoord.x / VelocityParams.y * 2.0 - 1.0, gl_FragCoord.y / VelocityParams.z * 2.0 - 1.0);
    vec4 clip = vec4(ndc, gl_FragCoord.z, 1.0);
    vec4 previous = ObjectReprojection * clip;
    bool invalidPixel = VelocityParams.x > 0.5 || previous.w <= 0.0 || previous.w != previous.w || isinf(previous.w);
    vec2 motion = vec2(0.0);
    if (!invalidPixel) {
        motion = previous.xy / previous.w - ndc;
        if (motion.x != motion.x || motion.y != motion.y ||
            abs(motion.x) >= INVALID_VELOCITY || abs(motion.y) >= INVALID_VELOCITY ||
            isinf(motion.x) || isinf(motion.y)) {
            invalidPixel = true;
        }
    }
    velocityColor = invalidPixel
        ? vec4(INVALID_VELOCITY, INVALID_VELOCITY, 0.0, 0.0)
        : vec4(motion, 0.0, 0.0);
}
