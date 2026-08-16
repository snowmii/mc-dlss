package me.snowmii.streamline;

import java.util.Objects;

/**
 * One frame's DLSS SR resources, tagged on the caller's command buffer in the units the flat
 * native ABI takes them.
 *
 * <p>{@link #commandBuffer} is the caller's shared Vulkan command buffer the tags are recorded
 * for - the same buffer the frame's motion pass and evaluation are recorded on. {@link #color}
 * and {@link #depth} are the engine's render-sized colour and depth images.
 *
 * <p>The motion source is never carried here: it is always the bridge's own motion image,
 * tagged from native state once it has been acquired for the configured dimensions - the
 * camera-only route fills it with {@link MotionRequest}, the velocity-MRT route merges the
 * scene companion into it with {@link FillVelocityRequest}. Direct companion tagging is
 * retired.
 */
public record SrTagRequest(
	/** The caller's shared Vulkan command buffer the tags are recorded for. */
	long commandBuffer,
	/** The engine's render-sized colour image. */
	ImageBinding color,
	/** The engine's render-sized depth image. */
	ImageBinding depth
) {
	public SrTagRequest {
		Objects.requireNonNull(color, "color");
		Objects.requireNonNull(depth, "depth");
	}

	/** Kotlin's all-defaults construction: zero command buffer and zeroed bindings. */
	public SrTagRequest() {
		this(0L, new ImageBinding(0L, 0L, 0), new ImageBinding(0L, 0L, 0));
	}
}