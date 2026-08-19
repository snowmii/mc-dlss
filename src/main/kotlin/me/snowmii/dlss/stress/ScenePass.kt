package me.snowmii.dlss.stress
import me.snowmii.dlss.render.mrt.VelocityContext
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
	val enabled: Boolean

	fun toggle(): Boolean

	fun readout(): String

	/**
	 * Draws the effect over [target], in place, with [camera] supplying the frame's unjittered
	 * world projection and view rotation.
	 *
	 * [velocity] is this frame's velocity-MRT write context when the open world phase offers
	 * its scene velocity view on the velocity route, or null on a vanilla or camera-only frame.
	 * A pass that writes camera motion binds its two-target pipeline and writes the view at
	 * color index 1 only for a non-null context; a null context keeps the one-target shape.
	 */
	fun render(target: RenderTarget, camera: DlssCameraSample, velocity: VelocityContext? = null)
}
