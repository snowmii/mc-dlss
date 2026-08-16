package me.snowmii.dlss.bridge

import me.snowmii.streamline.Vec2

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
 *
 * [camera] is the frame's real camera, carried through the same call so the evaluation's
 * single `slSetConstants` records it together with the jitter, motion scale, and reset flag
 * under the frame's retained token. Null records a zero-filled camera: an SR-only caller
 * without a camera (a test double, or a frame whose camera was never observed) still
 * evaluates, and the module records whatever the struct carried.
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
	/** The frame's real camera, or null to record a zero-filled camera. */
	val camera: CameraConstants? = null,
)
