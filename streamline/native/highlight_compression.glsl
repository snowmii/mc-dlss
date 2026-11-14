vec3 compressHighlights(vec3 radiance, float exposure) {
    if (any(isnan(radiance)) || any(isinf(radiance))) return vec3(0.0);
    vec3 color = max(radiance, vec3(0.0)) * max(exposure, 1.0e-4);
    float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
    const float knee = 0.3;
    const float shoulder = 0.15;
    if (luminance <= knee) return color;
    float compressed = knee + shoulder * log(1.0 + (luminance - knee) / shoulder);
    return color * (compressed / luminance);
}
