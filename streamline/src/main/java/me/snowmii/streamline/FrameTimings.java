package me.snowmii.streamline;

import org.jetbrains.annotations.NotNull;

/**
 * GPU milliseconds one completed frame spent in each stage the native bridge records.
 *
 * <p>The reason this exists at all is that neither number a reviewer can see from outside
 * answers where the time goes. Frame rate on a CPU-bound client is the same whether this chain
 * costs 0.2ms or 2ms, and GPU utilization is that cost divided by a frame length the renderer
 * chose, so it moves when the client's frame rate moves and says nothing about the chain
 * itself. These are device timestamps around the three recorded stages, which separate
 * evaluation cost from the copy and the barriers this module wraps it in.
 *
 * <p>{@code totalMs} is measured end to end rather than summed, so whatever the barriers
 * between the stages cost is inside it and not inside any of the three.
 */
public record FrameTimings(
	float motionMs,
	float evaluateMs,
	float presentMs,
	/** End to end, so barrier cost is here and not in the three stages. */
	float totalMs
) {
	/** One compact field for a diagnostic line. */
	@NotNull
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
