package me.snowmii.dlss.pass
import me.snowmii.dlss.render.DlssCameraSample
import com.mojang.blaze3d.pipeline.RenderTarget

/**
 * One effect the client draws over the world scene inside the world phase, before DLSS
 * evaluation runs.
 *
 * This is the seam the [StressRuntime] holder is the prototype of: a future shader loader
 * owns a list of these and renders them in registration order, all of them at the resolution
 * the world was actually rendered at - the low-resolution scene target on a DLSS frame, the
 * main target on a vanilla one. The stress pass is the first and only implementation today.
 *
 * A pass must never be the reason a frame does not finish: a failure is expected to disable
 * the pass for the session rather than throw out of the render loop.
 */
interface ScenePass : AutoCloseable {
	/** Whether the pass currently renders. */
	val enabled: Boolean

	/** Flips the pass on or off and returns the state now in effect. */
	fun toggle(): Boolean

	/** One line naming the workload, for the same readout the DLSS controls print. */
	fun readout(): String

	/**
	 * Draws the effect over [target], in place, with [camera] supplying the frame's unjittered
	 * world projection and view rotation.
	 */
	fun render(target: RenderTarget, camera: DlssCameraSample)
}
