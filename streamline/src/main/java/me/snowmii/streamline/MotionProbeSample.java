package me.snowmii.streamline;

/**
 * Centre-pixel motion and depth from the last readable probe slot.
 *
 * <p>{@code slot} is the ring index the GPU wrote two records ago, so a CPU reprojection stored
 * under the same index still names that frame.
 */
public record MotionProbeSample(float motionX, float motionY, float depth, int slot) {}
