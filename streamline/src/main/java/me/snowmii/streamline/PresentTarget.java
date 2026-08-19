package me.snowmii.streamline;

/**
 * The engine image the upscaled frame is copied into, in the units the flat native ABI takes
 * them.
 *
 * <p>This is Minecraft's own output-sized target - the one everything after the world phase
 * composes over - so the source of the copy is the bridge's output image and never appears
 * here. Nor does a view or a format: the copy needs only the image handle, and its subresource
 * range is derived natively.
 *
 * <p>{@link #outputDimensions} is stamped by the session adapter rather than supplied here,
 * for the same reason as in {@link EvaluationRequest}: it is the configured output size, and
 * it is carried so the bridge can refuse a destination that is not it.
 */
public record PresentTarget(
	long commandBuffer,
	long image,
	/** Stamped by the session adapter; see the class comment. */
	Dimensions outputDimensions
) {
	public PresentTarget() {
		this(0L, 0L, null);
	}

	/** Dimensions left null for the adapter to stamp. */
	public PresentTarget(long commandBuffer, long image) {
		this(commandBuffer, image, null);
	}
}