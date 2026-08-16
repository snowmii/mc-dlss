package me.snowmii.streamline;

/**
 * A two-component float pair, mirroring the ABI's {@code McDlssVec2}.
 *
 * <p>Both values the evaluation carries in this shape - the sub-pixel jitter offset and the
 * motion-vector scale - are a pair whose halves are meaningless apart, and both used to cross
 * as two adjacent {@code float} arguments where nothing but position distinguished x from y.
 */
public record Vec2(float x, float y) {}