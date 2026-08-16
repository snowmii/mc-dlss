#version 330

// Volumetric aurora stress pass.
//
// Deliberately expensive: a depth-aware raymarch through a domain-warped 3D FBM field, a short
// secondary march toward the sun for self-shadowing, and a radial screen-space godray gather.
// The cost is dominated by fragment work, which is exactly the workload DLSS is supposed to move
// off the critical path - it scales with the *render* resolution, so the same scene costs
// measurably less when the world phase is rendering into the low-resolution scene target.
//
// Nothing here reads or writes vanilla state. It runs on a copy of whatever target the world was
// rendered into, and everything after the world phase - hand, item, screen effects, HUD - is
// untouched.

uniform sampler2D InSampler;
uniform sampler2D InDepthSampler;

layout(std140) uniform StressConfig {
    mat4 InvViewProj;
    // xyz: camera world position. w: seconds since the pass started.
    vec4 CameraPos;
    // xyz: normalized world-space sun direction. w: effect intensity.
    vec4 SunDirection;
    // x: march steps. y: FBM octaves. z: NDC y sign for this backend. w: godray taps.
    vec4 MarchParams;
    // xy: render size in pixels. zw: sun position in UV space, or (-1,-1) when off screen.
    vec4 ScreenParams;
    // Jitter-stripped current-to-previous clip reprojection: maps a pixel's jittered clip
    // position - what the rendered frame and its reversed-Z depth actually hold - to where that
    // same surface sat in the previous frame, in the same jittered clip space.
    mat4 Reprojection;
    // x: 1.0 when this frame has no valid predecessor, so every pixel writes the invalid
    // sentinel instead of the identity-derived zero. yzw: unused.
    vec4 VelocityParams;
};

in vec2 texCoord;

out vec4 fragColor;
// The velocity twin's second color target: jitter-free NDC camera motion, written only when the
// pass is bound with the velocity attachment. On the one-target pipeline this output has no
// attachment and the write is discarded. The assignment must stay after `fragColor =` in main():
// Minecraft rewrites output locations by reflection order, which follows first-assignment order.
out vec4 velocityColor;

const int MAX_STEPS = 192;
const int MAX_OCTAVES = 8;
const int MAX_SUN_STEPS = 6;
const int MAX_GODRAY_TAPS = 48;

// The band the aurora occupies, in world Y. Chosen so it sits above terrain in an ordinary
// overworld and stays visible from the ground.
const float BAND_CENTER = 130.0;
const float BAND_WIDTH = 55.0;
const float MAX_DISTANCE = 480.0;

// The single representable sentinel for pixels with no valid motion: far outside the [-1, 1]
// NDC range a real camera-motion vector can reach, and exactly representable in the RG16_FLOAT
// payload. A frame with no predecessor, a pixel whose reprojection the previous camera cannot
// see, and any pixel whose derived vector is non-finite or out of range must all write this
// rather than the identity-derived zero (which would read as a camera that stood still) or an
// Inf/NaN payload value.
const float INVALID_VELOCITY = 10000.0;

float hash13(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

float valueNoise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);

    float n000 = hash13(i + vec3(0.0, 0.0, 0.0));
    float n100 = hash13(i + vec3(1.0, 0.0, 0.0));
    float n010 = hash13(i + vec3(0.0, 1.0, 0.0));
    float n110 = hash13(i + vec3(1.0, 1.0, 0.0));
    float n001 = hash13(i + vec3(0.0, 0.0, 1.0));
    float n101 = hash13(i + vec3(1.0, 0.0, 1.0));
    float n011 = hash13(i + vec3(0.0, 1.0, 1.0));
    float n111 = hash13(i + vec3(1.0, 1.0, 1.0));

    float x00 = mix(n000, n100, f.x);
    float x10 = mix(n010, n110, f.x);
    float x01 = mix(n001, n101, f.x);
    float x11 = mix(n011, n111, f.x);

    return mix(mix(x00, x10, f.y), mix(x01, x11, f.y), f.z);
}

float fbm(vec3 p, int octaves) {
    float sum = 0.0;
    float amplitude = 0.5;
    float normalization = 0.0;

    for (int o = 0; o < MAX_OCTAVES; o++) {
        if (o >= octaves) {
            break;
        }
        sum += amplitude * valueNoise(p);
        normalization += amplitude;
        p = p * 2.03 + vec3(11.7, 3.1, 7.3);
        amplitude *= 0.5;
    }

    return normalization > 0.0 ? sum / normalization : 0.0;
}

// Domain-warped density. The warp is what turns plain FBM into ribbons rather than clouds, and
// it costs three extra FBM evaluations per sample, which is most of this shader's price.
float auroraDensity(vec3 worldPosition, float time, int octaves) {
    vec3 p = worldPosition * 0.011;
    p.x += time * 0.035;
    p.z -= time * 0.021;

    int warpOctaves = max(octaves - 2, 1);
    vec3 warp = vec3(
        fbm(p + vec3(0.0, time * 0.05, 0.0), warpOctaves),
        fbm(p + vec3(5.2, 1.3, time * 0.04), warpOctaves),
        fbm(p + vec3(-3.7, 2.8, 1.9), warpOctaves)
    );

    float d = fbm(p + 2.1 * (warp - 0.5), octaves);

    // Vertical band, so the effect reads as a sky feature rather than uniform fog.
    float height = (worldPosition.y - BAND_CENTER) / BAND_WIDTH;
    float band = exp(-height * height);

    // Thin sheets: the sharp remap is what makes the ribbons look like curtains.
    float sheets = smoothstep(0.48, 0.86, d);

    return sheets * band;
}

vec3 auroraColor(float heightFraction, float density) {
    vec3 low = vec3(0.06, 0.95, 0.55);
    vec3 high = vec3(0.22, 0.42, 1.00);
    vec3 hot = vec3(1.00, 0.28, 0.78);

    vec3 color = mix(low, high, clamp(heightFraction, 0.0, 1.0));
    return mix(color, hot, pow(clamp(density, 0.0, 1.0), 3.0));
}

// Henyey-Greenstein phase function.
float phaseHG(float cosTheta, float g) {
    float gg = g * g;
    float denominator = 1.0 + gg - 2.0 * g * cosTheta;
    return (1.0 - gg) / (4.0 * 3.14159265 * pow(max(denominator, 1e-4), 1.5));
}

vec3 unproject(vec2 ndc, float depth) {
    vec4 clip = InvViewProj * vec4(ndc, depth, 1.0);
    return clip.xyz / clip.w;
}

vec3 acesToneMap(vec3 color) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return clamp((color * (a * color + b)) / (color * (c * color + d) + e), 0.0, 1.0);
}

void main() {
    vec3 sceneColor = texture(InSampler, texCoord).rgb;
    float sceneDepth = texture(InDepthSampler, texCoord).r;

    float time = CameraPos.w;
    int steps = int(clamp(MarchParams.x, 1.0, float(MAX_STEPS)));
    int octaves = int(clamp(MarchParams.y, 1.0, float(MAX_OCTAVES)));
    int godrayTaps = int(clamp(MarchParams.w, 0.0, float(MAX_GODRAY_TAPS)));

    vec2 ndc = vec2(texCoord.x * 2.0 - 1.0, (texCoord.y * 2.0 - 1.0) * MarchParams.z);

    // Jitter-free NDC camera motion, the payload DLSS reads. The rendered frame is jittered, so
    // the clip position below carries this frame's offset; the reprojection strips it, walking
    // the surface point back to where it sat in the previous frame, and subtracting the current
    // NDC leaves the motion vector. Reversed-Z depth goes straight into clip.z - 1.0 is the near
    // plane, 0.0 the far plane - and the reprojection was composed from the same depth
    // convention, so flipping or inverting it here would point previous-frame positions at the
    // wrong surface.
    vec4 clip = vec4(ndc, sceneDepth, 1.0);
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
    // Reversed-Z: 1.0 is the near plane, 0.0 the far plane.
    vec3 nearPosition = unproject(ndc, 1.0);
    vec3 farPosition = unproject(ndc, 0.0);
    vec3 rayDirection = normalize(farPosition - nearPosition);

    // Distance to whatever the world drew here, so the volume is occluded by terrain instead of
    // painted over it. A depth of exactly 0.0 is the cleared far plane: nothing was drawn.
    float sceneDistance = MAX_DISTANCE;
    if (sceneDepth > 0.0) {
        sceneDistance = min(length(unproject(ndc, sceneDepth) - nearPosition), MAX_DISTANCE);
    }

    // The world is rendered camera-relative, so unprojected positions are camera-relative too;
    // adding the camera puts the noise field in absolute world space, which keeps the aurora
    // anchored to the world rather than dragged along with the player.
    vec3 origin = CameraPos.xyz + nearPosition;
    vec3 sunDirection = normalize(SunDirection.xyz);

    float stepSize = sceneDistance / float(steps);
    // Per-pixel dither, otherwise a low step count bands badly. Fixed in screen space rather than
    // animated: a time-varying dither moves which samples land inside the density field, so the
    // same static camera cost a different amount every frame - and it also feeds DLSS per-frame
    // noise to accumulate, in the one pass whose whole purpose is a stable measurement.
    float dither = hash13(vec3(gl_FragCoord.xy, 0.0));

    float transmittance = 1.0;
    vec3 scattered = vec3(0.0);
    float cosTheta = dot(rayDirection, sunDirection);
    float phase = phaseHG(cosTheta, 0.62) + 0.35 * phaseHG(cosTheta, -0.18);

    // Every pixel walks the same number of steps and pays the same work at each one. The march
    // used to stop early once transmittance ran out and to skip the sun march on thin samples,
    // which made a pixel's cost depend on how much aurora it happened to look through: turning the
    // camera into or away from the ribbons moved the frame time in steps, and a frame-pacing
    // measurement cannot read anything through that. Density decides how much a sample
    // contributes, never whether it is computed.
    for (int i = 0; i < MAX_STEPS; i++) {
        if (i >= steps) {
            break;
        }

        float distanceAlongRay = (float(i) + dither) * stepSize;
        vec3 samplePosition = origin + rayDirection * distanceAlongRay;
        float density = auroraDensity(samplePosition, time, octaves);

        // Short secondary march toward the sun, so the curtains shadow themselves. Unconditional:
        // this is the dominant cost, so branching on it is what the swing was made of. An empty
        // sample still pays it and still adds nothing, because `absorbed` goes to zero with
        // density.
        float sunTransmittance = 1.0;
        for (int s = 1; s <= MAX_SUN_STEPS; s++) {
            vec3 sunSample = samplePosition + sunDirection * (float(s) * 9.0);
            float sunDensity = auroraDensity(sunSample, time, max(octaves - 2, 1));
            sunTransmittance *= exp(-sunDensity * 0.55);
        }

        float heightFraction = clamp((samplePosition.y - (BAND_CENTER - BAND_WIDTH)) / (2.0 * BAND_WIDTH), 0.0, 1.0);
        vec3 emission = auroraColor(heightFraction, density) * (0.35 + 3.2 * phase * sunTransmittance);

        float absorbed = 1.0 - exp(-density * stepSize * 0.085);
        scattered += transmittance * absorbed * emission;
        transmittance *= 1.0 - absorbed;
    }

    vec3 color = sceneColor * transmittance + scattered * SunDirection.w;

    // Radial godrays from the sun's screen position over the scene's bright pixels. Screen space,
    // so it costs another pass over the frame with no relation to the march above.
    vec2 sunUv = ScreenParams.zw;
    // The gather runs whenever taps are configured, on or off screen. Making it conditional on the
    // sun being in frame deleted every tap from the frame's cost in a single camera turn - the
    // largest step this pass could put in a frame-time trace. An off-screen sun gathers along a
    // clamped direction and is multiplied out of the result instead.
    if (godrayTaps > 0) {
        vec2 gatherUv = clamp(sunUv, 0.0, 1.0);
        float sunOnScreen = (sunUv.x >= -0.5 && sunUv.y >= -0.5) ? 1.0 : 0.0;
        vec2 delta = (texCoord - gatherUv) / float(godrayTaps) * 0.85;
        vec2 sampleUv = texCoord;
        float decay = 1.0;
        vec3 rays = vec3(0.0);

        for (int t = 0; t < MAX_GODRAY_TAPS; t++) {
            if (t >= godrayTaps) {
                break;
            }
            sampleUv -= delta;
            vec3 tap = texture(InSampler, clamp(sampleUv, 0.0, 1.0)).rgb;
            float luminance = dot(tap, vec3(0.2126, 0.7152, 0.0722));
            rays += tap * smoothstep(0.72, 1.0, luminance) * decay;
            decay *= 0.96;
        }

        float towardSun = clamp(1.0 - length(texCoord - gatherUv), 0.0, 1.0);
        color += rays / float(godrayTaps) * 1.8 * towardSun * SunDirection.w * sunOnScreen;
    }

    // Grade: mild bloom-free exposure lift, ACES, and a vignette that keeps the ribbons the
    // brightest thing on screen.
    color = acesToneMap(color * 1.12);

    vec2 centered = texCoord - 0.5;
    float vignette = 1.0 - 0.55 * dot(centered, centered);
    color *= vignette;

    fragColor = vec4(color, 1.0);

    // The velocity twin's second color target: jitter-free NDC camera motion. Assigned after
    // fragColor on purpose - Minecraft rewrites every fragment output's Location decoration to
    // its index in the spirv-cross reflection list, and glslang emits outputs in first-assignment
    // order inside main(), so keeping the final fragColor assignment first in the module is what
    // pins scene color at attachment 0 and this payload at attachment 1. On the one-target
    // pipeline this output has no attachment and the write is discarded.
    velocityColor = invalidPixel ? vec4(INVALID_VELOCITY, INVALID_VELOCITY, 0.0, 0.0) : vec4(motion, 0.0, 0.0);
}
