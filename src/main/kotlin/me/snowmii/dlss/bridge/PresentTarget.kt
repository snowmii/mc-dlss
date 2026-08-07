package me.snowmii.dlss.bridge

/**
 * The engine image the upscaled frame is copied into, in the units the flat native ABI takes
 * them.
 *
 * This is Minecraft's own output-sized target - the one everything after the world phase
 * composes over - so the source of the copy is the bridge's output image and never appears here.
 * Nor does a view or a format: the copy needs only the image handle, and its subresource range
 * is derived natively.
 *
 * [outputDimensions] is stamped by [me.snowmii.dlss.session.LifecycleAdapter] rather than
 * supplied here, for the same reason as in [EvaluationRequest]: it is the configured output
 * size, and it is carried so the bridge can refuse a destination that is not it.
 */
data class PresentTarget(
	val commandBuffer: Long = 0,
	val image: Long = 0,
	/** Stamped by the adapter; see the class comment. */
	val outputDimensions: DlssDimensions? = null,
)
