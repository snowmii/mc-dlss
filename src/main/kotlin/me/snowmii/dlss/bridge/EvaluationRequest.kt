package me.snowmii.dlss.bridge

/**
 * One DLSS evaluation, in the units the flat native ABI takes them.
 *
 * Only the engine's two images are carried. The motion and output images are the bridge's own -
 * allocated from the configured dimensions and reachable natively - so passing them back would
 * be handing the bridge handles it already holds.
 *
 * [renderDimensions] is not filled by whoever describes the frame. It is the size the *native
 * configuration* is on, which only [me.snowmii.dlss.session.LifecycleAdapter] knows, and it is
 * carried purely so the bridge can check that its caller has not lost track of the
 * configuration it asked for. The adapter stamps it on the way through.
 */
data class EvaluationRequest(
	val commandBuffer: Long = 0,
	val color: ImageBinding = ImageBinding(0, 0, 0),
	val depth: ImageBinding = ImageBinding(0, 0, 0),
	/** Sub-pixel offset of this frame, in render pixels - the unit NGX takes it in. */
	val jitter: Vec2 = Vec2(0f, 0f),
	val motionScale: Vec2 = Vec2(0f, 0f),
	val frameTimeMilliseconds: Float = 0f,
	val resetHistory: Boolean = false,
	/** Stamped by the adapter; see the class comment. */
	val renderDimensions: DlssDimensions? = null,
)
