package me.snowmii.dlss.bridge

/**
 * One post-scene velocity merge, in the units the flat native ABI takes them.
 *
 * On the velocity-MRT route the scene's RG16_FLOAT velocity companion carries object motion
 * from the retained writers and the invalid sentinel everywhere else. The fill dispatch
 * samples [depth] and [velocity], copies every non-sentinel vector unchanged and reprojects
 * every sentinel pixel through the same jitter-stripped camera [reprojection] the camera-only
 * writer uses, and stores the complete merged field into the native motion image - the sole
 * Streamline motion source. The destination is the native motion image, which the bridge
 * owns, so it never appears here; [velocity] is a sampled input and is never bound as
 * storage.
 *
 * [reset] marks a frame with no valid predecessor. Such a frame's reprojection is the
 * identity, which would read as "the camera stood still", so the fill writes the invalid
 * sentinel everywhere instead of reconstructing anything.
 *
 * [reprojection] is the 16 column-major floats of `DlssFrameMotion.reprojection`.
 *
 * [renderDimensions] is stamped by [me.snowmii.dlss.session.LifecycleAdapter] rather than
 * supplied here, for the same reason as in [EvaluationRequest].
 */
data class FillVelocityRequest(
	val commandBuffer: Long = 0,
	val depth: ImageBinding = ImageBinding(0, 0, 0),
	val velocity: ImageBinding = ImageBinding(0, 0, 0),
	val reprojection: FloatArray = FloatArray(16),
	val reset: Boolean = false,
	/** Stamped by the adapter; see the class comment. */
	val renderDimensions: DlssDimensions? = null,
) {
	// FloatArray has identity equals, which a data class would silently inherit.
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is FillVelocityRequest) return false
		return commandBuffer == other.commandBuffer &&
			depth == other.depth &&
			velocity == other.velocity &&
			reprojection.contentEquals(other.reprojection) &&
			reset == other.reset &&
			renderDimensions == other.renderDimensions
	}

	override fun hashCode(): Int {
		var result = commandBuffer.hashCode()
		result = 31 * result + depth.hashCode()
		result = 31 * result + velocity.hashCode()
		result = 31 * result + reprojection.contentHashCode()
		result = 31 * result + reset.hashCode()
		result = 31 * result + renderDimensions.hashCode()
		return result
	}
}
