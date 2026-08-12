#version 330

// Hand/item velocity twin. Vanilla item color logic stays byte-for-byte in fragColor;
// velocityColor is written only by the second RG16_FLOAT MRT target.
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

// ObjectReprojection maps this frame's clip position to the hand's previous-frame clip
// position. VelocityParams.x is the reset/invalid flag; yz is the scene velocity viewport.
layout(std140) uniform HandVelocityConfig {
    mat4 ObjectReprojection;
    vec4 VelocityParams;
};

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;

// Keep output declaration order: target 0 scene color, target 1 motion payload.
out vec4 fragColor;
out vec4 velocityColor;

const float INVALID_VELOCITY = 10000.0;

void main() {
    vec4 color = texture(Sampler0, texCoord0);
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif

    color *= vertexColor * ColorModulator;
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    color *= lightMapColor;

    // Vanilla scene output remains target 0 and keeps its original fog/color path.
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);

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
