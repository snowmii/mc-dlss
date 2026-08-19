package me.snowmii.streamline;

import java.util.Objects;

/**
 * One frame's DLSS SR resources, tagged on the caller's command buffer.
 *
 * <p>Motion is never carried: it is always the bridge's own motion image, tagged from native
 * state once acquired. Camera-only fills it with {@link MotionRequest}; velocity-MRT merges
 * the scene companion with {@link FillVelocityRequest}.
 */
public record SrTagRequest(
	long commandBuffer,
	ImageBinding color,
	ImageBinding depth
) {
	public SrTagRequest {
		Objects.requireNonNull(color, "color");
		Objects.requireNonNull(depth, "depth");
	}

	/** Kotlin all-defaults: zero command buffer and zeroed bindings. */
	public SrTagRequest() {
		this(0L, new ImageBinding(0L, 0L, 0), new ImageBinding(0L, 0L, 0));
	}
}