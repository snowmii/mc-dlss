package me.snowmii.dlss.bridge

/**
 * GPU milliseconds one completed frame spent in each stage the native bridge records.
 *
 * The reason this exists at all is that neither number a reviewer can see from outside answers
 * where the time goes. Frame rate on a CPU-bound client is the same whether this chain costs
 * 0.2ms or 2ms, and GPU utilization is that cost divided by a frame length the renderer chose,
 * so it moves when the client's frame rate moves and says nothing about the chain itself. These
 * are device timestamps around the three recorded stages, which separate NGX's own cost from the
 * copy and the barriers this module wraps it in.
 *
 * [totalMs] is measured end to end rather than summed, so whatever the barriers between the
 * stages cost is inside it and not inside any of the three.
 */
data class DlssFrameTimings(
	/** The camera-motion compute pass that fills the motion image. */
	val motionMs: Float,
	/** The NGX evaluation itself. */
	val evaluateMs: Float,
	/** The copy of the upscaled output into Minecraft's target. */
	val presentMs: Float,
	/** First stamp to last: the whole chain, including the barriers between the stages. */
	val totalMs: Float,
) {
	/** One compact field for a diagnostic line. */
	override fun toString(): String =
		"total=%.2fms motion=%.2fms evaluate=%.2fms present=%.2fms".format(
			totalMs,
			motionMs,
			evaluateMs,
			presentMs,
		)
}
