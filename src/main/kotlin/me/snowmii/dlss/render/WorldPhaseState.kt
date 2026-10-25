package me.snowmii.dlss.render

import com.mojang.blaze3d.pipeline.RenderTarget
import me.snowmii.dlss.api.RayReconstruction
import me.snowmii.streamline.Dimensions

/**
 * The sequences a DLSS frame accumulates against its predecessor, and the values this frame
 * published from them.
 *
 * Jitter and camera motion are sequences whose current term is only meaningful relative to the frame
 * before it, the camera sequences are rebuilt from render dimensions when those change, and both
 * are broken by exactly the same events - a vanilla frame, a lost frame, a replaced scene.
 *
 * The published values are read by the world projection and by the evaluation, which both have
 * to describe the *same* offset. The phase advances the sequence exactly once and publishes the
 * single value both of them read.
 */
class WorldPhaseState {
	private var jitter: DlssJitter? = null
	private var motion: DlssCameraMotion? = null

	var activeJitter: DlssJitterOffset? = null
		private set

	var activeMotion: DlssFrameMotion? = null
		private set

	/** Whether the sequences exist yet, which they do only after a successful native startup. */
	val started: Boolean
		get() = jitter != null

	/**
	 * Rebuilds both sequences for a new render size: the jitter sequence whose length is the
	 * pixel ratio, and the motion reprojection whose scale is the render dimensions.
	 */
	fun rebuild(renderDimensions: Dimensions, outputDimensions: Dimensions) {
		jitter = DlssJitter(renderDimensions, outputDimensions)
		motion = DlssCameraMotion(renderDimensions)
	}

	/**
	 * Advances both sequences for a phase rendering into [target] and publishes what they gave.
	 *
	 * A vanilla frame - [target] null - breaks the accumulated history, so it restarts the
	 * sequence rather than consuming a phase no evaluation will ever see. A frame whose camera
	 * was never observed publishes no motion and breaks the motion chain, because it cannot be
	 * reprojected against.
	 */
	fun open(target: RenderTarget?, camera: DlssCameraSample?, nowNanos: Long) {
		val offset = if (target != null) {
			jitter?.advance(RayReconstruction.enabled)
		} else {
			jitter?.reset()
			null
		}
		activeJitter = offset
		activeMotion = if (offset != null && camera != null) {
			motion?.advance(camera, offset, nowNanos)
		} else {
			motion?.reset()
			null
		}
	}

	/**
	 * Finishes the phase and clears active jitter/motion.
	 */
	fun finish() {
		activeJitter = null
		activeMotion = null
	}

	/**
	 * Forgets the camera the next frame would reproject against.
	 */
	fun resetMotion() {
		motion?.reset()
	}

	/**
	 * Forgets everything accumulated: the camera and the jitter phase both.
	 */
	fun reset() {
		jitter?.reset()
		motion?.reset()
	}

	/** Drops the sequences entirely, for a runtime that is shutting down. */
	fun discard() {
		jitter = null
		motion = null
	}
}
