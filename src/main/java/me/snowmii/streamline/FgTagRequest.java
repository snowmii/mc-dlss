package me.snowmii.streamline;

import java.util.Objects;

/**
 * One frame's DLSS-G resources, tagged on the caller's command buffer in the units the flat
 * native ABI takes them.
 *
 * <p>{@link #commandBuffer} is the caller's shared Vulkan command buffer the tags are recorded
 * for. {@link #depth} is the engine's render-sized depth image, which must be D32_SFLOAT;
 * {@link #hudless} is the engine's output-sized HUD-less colour and {@link #ui} its
 * output-sized UI colour+alpha target, both of which must be R8G8B8A8_UNORM - the same
 * formats the native side's FG options record, and anything else is refused.
 *
 * <p>The motion source is never carried here: it is always the bridge's own motion image,
 * tagged from native state. The native side refuses the call until the DLSS-G options
 * recorded for the stored configuration and the motion image was acquired for the configured
 * dimensions - the camera-only route fills it with {@link MotionRequest}, the velocity-MRT
 * route merges the scene companion into it with {@link FillVelocityRequest}. Direct companion
 * tagging is retired. The backbuffer/output chain is present interception rather than a tag,
 * so no output image is carried either.
 */
public record FgTagRequest(
	/** The caller's shared Vulkan command buffer the tags are recorded for. */
	long commandBuffer,
	/** The engine's render-sized depth image, D32_SFLOAT. */
	ImageBinding depth,
	/** The engine's output-sized HUD-less colour, R8G8B8A8_UNORM. */
	ImageBinding hudless,
	/** The engine's output-sized UI colour+alpha target, R8G8B8A8_UNORM. */
	ImageBinding ui
) {
	public FgTagRequest {
		Objects.requireNonNull(depth, "depth");
		Objects.requireNonNull(hudless, "hudless");
		Objects.requireNonNull(ui, "ui");
	}
}