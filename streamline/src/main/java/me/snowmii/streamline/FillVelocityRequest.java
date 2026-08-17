package me.snowmii.streamline;

import java.util.Arrays;
import java.util.Objects;

/**
 * One post-scene velocity merge, in the units the flat native ABI takes them.
 *
 * <p>On the velocity-MRT route the scene's RG16_FLOAT velocity companion carries object motion
 * from the retained writers and the invalid sentinel everywhere else. The fill dispatch
 * samples {@link #depth} and {@link #velocity}, copies every non-sentinel vector unchanged
 * and reprojects every sentinel pixel through the same jitter-stripped camera
 * {@link #reprojection} the camera-only writer uses, and stores the complete merged field into
 * the native motion image - the sole Streamline motion source. The destination is the native
 * motion image, which the bridge owns, so it never appears here; {@link #velocity} is a
 * sampled input and is never bound as storage.
 *
 * <p>{@link #reset} marks a frame with no valid predecessor. Such a frame's reprojection is
 * the identity, which would read as "the camera stood still", so the fill writes the invalid
 * sentinel everywhere instead of reconstructing anything.
 *
 * <p>{@link #reprojection} is the 16 column-major floats of
 * {@code DlssFrameMotion.reprojection}.
 *
 * <p>{@link #renderDimensions} is stamped by the session adapter rather than supplied here,
 * for the same reason as in {@link EvaluationRequest}.
 */
public record FillVelocityRequest(
	/** The caller's shared Vulkan command buffer the fill is recorded on. */
	long commandBuffer,
	/** The engine's render-sized depth image. */
	ImageBinding depth,
	/** The engine's sparse RG16_FLOAT velocity companion. */
	ImageBinding velocity,
	/** 16 column-major reprojection floats, the same step the camera-only writer uses. */
	float[] reprojection,
	/** A frame with no valid predecessor writes the invalid sentinel everywhere. */
	boolean reset,
	/** Stamped by the session adapter; see the class comment. */
	Dimensions renderDimensions
) {
	public FillVelocityRequest {
		Objects.requireNonNull(depth, "depth");
		Objects.requireNonNull(velocity, "velocity");
		Objects.requireNonNull(reprojection, "reprojection");
	}

	/**
	 * Compares the reprojection payload, not the array identity the generated {@code equals}
	 * would compare - the Kotlin data class this record replaces did the same, or two
	 * requests holding the same step in different arrays would answer unequal.
	 */
	@Override
	public boolean equals(Object other) {
		return other instanceof FillVelocityRequest(
			long buffer, ImageBinding depth1, ImageBinding velocity1, float[] reprojection1, boolean reset1, Dimensions dimensions
		)
			&& buffer == commandBuffer
			&& depth1.equals(depth)
			&& velocity1.equals(velocity)
			&& Arrays.equals(reprojection1, reprojection)
			&& reset1 == reset
			&& Objects.equals(dimensions, renderDimensions);
	}

	@Override
	public int hashCode() {
		int result = Long.hashCode(commandBuffer);
		result = 31 * result + depth.hashCode();
		result = 31 * result + velocity.hashCode();
		result = 31 * result + Arrays.hashCode(reprojection);
		result = 31 * result + Boolean.hashCode(reset);
		result = 31 * result + Objects.hashCode(renderDimensions);
		return result;
	}

	/**
	 * Kotlin's all-defaults construction: zero command buffer, zeroed bindings, fresh 16
	 * reprojection floats, no reset, null dimensions.
	 */
	public FillVelocityRequest() {
		this(0L, new ImageBinding(0L, 0L, 0), new ImageBinding(0L, 0L, 0), new float[16], false, null);
	}

	/**
	 * Kotlin's trailing-default form: dimensions and reset left null/false for the adapter
	 * to stamp. Each construction still gets a fresh reprojection array, as the Kotlin
	 * default did.
	 */
	public FillVelocityRequest(long commandBuffer, ImageBinding depth, ImageBinding velocity, float[] reprojection, boolean reset) {
		this(commandBuffer, depth, velocity, reprojection, reset, null);
	}
}
