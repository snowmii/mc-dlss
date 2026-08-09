package me.snowmii.dlss.render

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.textures.GpuTextureView
import me.snowmii.dlss.session.DlssFrameRoute

/**
 * The GPU objects one DLSS configuration owns, and the stall that makes freeing them safe.
 *
 * Two things are held: the low-resolution scene target the world renders into, and the
 * native-owned motion and output images the evaluation writes through. Both are sized from the
 * configuration, so both are *released* rather than resized whenever it stops applying - a frame
 * evaluating into images sized for the previous configuration is the failure that silently
 * latches full resolution.
 *
 * The reason they are held together is the stall. Destroying a resource Minecraft's in-flight
 * frames still read is the one Vulkan error nothing reports where it happens: the release
 * succeeds, and the device is lost several frames later inside an unrelated semaphore wait.
 * Every free here is preceded by a device wait, and the wait is *guarded* - taken only when a
 * release is actually about to happen - so the steady state, same route and same size every
 * frame, never pays for it. Spreading that rule across the callers that free things is what this
 * class exists to prevent.
 */
class FrameResources(
	private val sceneTarget: SceneTarget,
	/**
	 * Records this frame's DLSS work, or null for a runtime that only routes targets.
	 */
	val evaluation: FrameEvaluation?,
	/**
	 * Blocks until the device has finished every frame already submitted, or does nothing for a
	 * runtime with no device behind it.
	 */
	private val quiesce: () -> Unit,
) : AutoCloseable {
	/** The scene target currently held, or null when the last route did not need one. */
	val currentTarget: RenderTarget?
		get() = sceneTarget.current

	/**
	 * The velocity view of the held scene target, or null when the last route did not need one.
	 *
	 * The companion is sized from the same route as the scene target and freed with it, so a
	 * null here means there is no scene-sized velocity attachment this frame - and terrain
	 * passes must stay vanilla.
	 */
	val currentVelocityView: GpuTextureView?
		get() = sceneTarget.currentVelocity?.colorTextureView

	/**
	 * The scene target for [route], stalling first if satisfying it will free the one held.
	 *
	 * A route that cannot reuse the held target makes the acquire release it, and the frames
	 * that drew into it can still be in flight.
	 */
	fun acquire(route: WorldTargetRoute): RenderTarget? {
		val releasesTarget = route.frame.route != DlssFrameRoute.DLSS ||
			sceneTarget.currentDimensions != route.worldDimensions
		if (sceneTarget.current != null && releasesTarget) {
			quiesce()
		}
		return sceneTarget.acquire(route)
	}

	/**
	 * Frees what is held, stalling first if anything is.
	 *
	 * [releaseImages] is false on a per-frame vanilla route - the native images are not sized
	 * from the frame, and re-acquiring them on every vanilla stretch would cross the ABI for no
	 * gain - and true whenever the configuration stops applying (toggle, reconfiguration, close).
	 */
	fun release(releaseImages: Boolean) {
		if (sceneTarget.current != null || (releaseImages && evaluation?.evaluationImages != null)) {
			quiesce()
		}
		if (releaseImages) {
			evaluation?.close()
		}
		sceneTarget.close()
	}

	/**
	 * Frees everything, stalling unconditionally first.
	 *
	 * A session being closed may have submitted frames in flight that nothing held tracks, so
	 * close never gambles on [release]'s guard. That guard then waits again when resources are
	 * held; close runs once per client.
	 */
	override fun close() {
		quiesce()
		release(releaseImages = true)
	}
}
