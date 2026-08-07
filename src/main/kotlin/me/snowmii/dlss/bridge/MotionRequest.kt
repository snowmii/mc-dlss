package me.snowmii.dlss.bridge

/**
 * One motion pass, in the units the flat native ABI takes them.
 *
 * [reprojection] is the 16 column-major floats of `DlssFrameMotion.reprojection`, which maps a
 * jittered clip position to the previous frame's unjittered one. The destination is the native
 * motion image, which the bridge owns, so it never appears here.
 *
 * [renderDimensions] is stamped by [me.snowmii.dlss.session.LifecycleAdapter] rather than
 * supplied here, for the same reason as in [EvaluationRequest].
 */
data class MotionRequest(
	val commandBuffer: Long = 0,
	val depth: ImageBinding = ImageBinding(0, 0, 0),
	val reprojection: FloatArray = FloatArray(16),
	/** Stamped by the adapter; see the class comment. */
	val renderDimensions: DlssDimensions? = null,
) {
	// FloatArray has identity equals, which a data class would silently inherit.
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is MotionRequest) return false
		return commandBuffer == other.commandBuffer &&
			depth == other.depth &&
			reprojection.contentEquals(other.reprojection) &&
			renderDimensions == other.renderDimensions
	}

	override fun hashCode(): Int {
		var result = commandBuffer.hashCode()
		result = 31 * result + depth.hashCode()
		result = 31 * result + reprojection.contentHashCode()
		result = 31 * result + renderDimensions.hashCode()
		return result
	}
}
