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
	/** The caller's shared Vulkan command buffer the pass is recorded on. */
	long commandBuffer,
	/** The engine's render-sized depth image. */
	ImageBinding depth,
	/** 16 column-major reprojection floats mapping this frame's clip to the previous one's. */
	float[] reprojection,
	/** Stamped by the session adapter; see the class comment. */
	Dimensions renderDimensions
) {
	public MotionRequest {
		Objects.requireNonNull(depth, "depth");
		Objects.requireNonNull(reprojection, "reprojection");
	}

	/**
	 * Compares the reprojection payload, not the array identity the generated {@code equals}
	 * would compare - the Kotlin data class this record replaces did the same.
	 */
	@Override
	public boolean equals(Object other) {
		return other instanceof MotionRequest request
			&& request.commandBuffer == commandBuffer
			&& request.depth.equals(depth)
			&& Arrays.equals(request.reprojection, reprojection)
			&& Objects.equals(request.renderDimensions, renderDimensions);
	}

	@Override
	public int hashCode() {
		int result = Long.hashCode(commandBuffer);
		result = 31 * result + depth.hashCode();
		result = 31 * result + Arrays.hashCode(reprojection);
		result = 31 * result + Objects.hashCode(renderDimensions);
		return result;
	}
}