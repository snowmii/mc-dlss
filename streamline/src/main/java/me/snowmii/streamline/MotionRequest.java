package me.snowmii.streamline;

import java.util.Arrays;
import java.util.Objects;

/**
 * One motion pass, in the units the flat native ABI takes them.
 *
 * <p>{@link #reprojection} is the 16 column-major floats of
 * {@code DlssFrameMotion.reprojection}, which maps a jittered clip position to the previous
 * frame's unjittered one. The destination is the native motion image, which the bridge owns,
 * so it never appears here.
 *
 * <p>{@link #renderDimensions} is stamped by the session adapter rather than supplied here,
 * for the same reason as in {@link EvaluationRequest}.
 */
public record MotionRequest(
	long commandBuffer,
	ImageBinding depth,
	/** 16 column-major floats: this frame's jittered clip to previous unjittered clip. */
	float[] reprojection,
	/** Stamped by the session adapter; see the class comment. */
	Dimensions renderDimensions
) {
	public MotionRequest {
		Objects.requireNonNull(depth, "depth");
		Objects.requireNonNull(reprojection, "reprojection");
	}

	/**
	 * Compares the reprojection payload, not array identity — two requests holding the same
	 * step in different arrays must be equal.
	 */
	@Override
	public boolean equals(Object other) {
		return other instanceof MotionRequest(long buffer, ImageBinding depth1, float[] reprojection1, Dimensions dimensions)
			&& buffer == commandBuffer
			&& depth1.equals(depth)
			&& Arrays.equals(reprojection1, reprojection)
			&& Objects.equals(dimensions, renderDimensions);
	}

	@Override
	public int hashCode() {
		int result = Long.hashCode(commandBuffer);
		result = 31 * result + depth.hashCode();
		result = 31 * result + Arrays.hashCode(reprojection);
		result = 31 * result + Objects.hashCode(renderDimensions);
		return result;
	}

	public MotionRequest() {
		this(0L, new ImageBinding(0L, 0L, 0), new float[16], null);
	}

	/** Dimensions left null for the adapter to stamp. Fresh reprojection array each call. */
	public MotionRequest(long commandBuffer, ImageBinding depth, float[] reprojection) {
		this(commandBuffer, depth, reprojection, null);
	}
}
