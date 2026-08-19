package me.snowmii.streamline;

/**
 * Two-component float pair, mirroring {@code McDlssVec2}. Halves are meaningless apart
 * (jitter offset, motion-vector scale).
 */
public record Vec2(float x, float y) {}