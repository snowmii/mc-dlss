package me.snowmii.streamline;

/**
 * GPU milliseconds one completed frame spent in each stage the native bridge records.
 *
 * <p>The reason this exists at all is that neither number a reviewer can see from outside
 * answers where the time goes. Frame rate on a CPU-bound client is the same whether this chain
 * costs 0.2ms or 2ms, and GPU utilization is that cost divided by a frame length the renderer
 * chose, so it moves when the client's frame rate moves and says nothing about the chain
 * itself. These are device timestamps around the three recorded stages, which separate NGX's
 * own cost from the copy and the barriers this module wraps it in.
 *
 * <p>{@code totalMs} is measured end to end rather than summed, so whatever the barriers
 * between the stages cost is inside it and not inside any of the three.
 */
public record FrameTimings(
	/** The camera-motion compute pass that fills the motion image. */
	float motionMs,
	/** The NGX evaluation itself. */
	float evaluateMs,
	/** The copy of the upscaled output into Minecraft's target. */
	float presentMs,
	/** First stamp to last: the whole chain, including the barriers between the stages. */
	float totalMs
) {
	/** One compact field for a diagnostic line. */
	@Override
	public String toString() {
		return String.format(
			"total=%.2fms motion=%.2fms evaluate=%.2fms present=%.2fms",
			totalMs,
			motionMs,
			evaluateMs,
			presentMs
		);
	}
}