package me.snowmii.streamline;

import java.util.Objects;

/**
 * One frame's DLSS-G resources, tagged on the caller's command buffer.
 *
 * <p>{@link #depth} must be D32_SFLOAT; {@link #hudless} and {@link #ui} must be
 * R8G8B8A8_UNORM — the formats the native FG options record. Anything else is refused.
 * Motion is the bridge's own image; native refuses until FG options are recorded and that
 * image is acquired. No output image: the backbuffer chain is present interception.
 */
public record FgTagRequest(
	long commandBuffer,
	ImageBinding depth,
	ImageBinding hudless,
	ImageBinding ui
) {
	public FgTagRequest {
		Objects.requireNonNull(depth, "depth");
		Objects.requireNonNull(hudless, "hudless");
		Objects.requireNonNull(ui, "ui");
	}

	/** Kotlin all-defaults: zero command buffer and zeroed bindings. */
	public FgTagRequest() {
		this(0L, new ImageBinding(0L, 0L, 0), new ImageBinding(0L, 0L, 0), new ImageBinding(0L, 0L, 0));
	}
}