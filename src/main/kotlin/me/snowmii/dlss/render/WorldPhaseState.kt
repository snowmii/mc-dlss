package me.snowmii.dlss.render

import com.mojang.blaze3d.pipeline.RenderTarget
import me.snowmii.dlss.bridge.DlssDimensions

/**
 * The two sequences a DLSS frame accumulates against its predecessor, and the values this frame
 * published from them.
 *
 * Jitter and camera motion are one concern rather than two: both are sequences whose current
 * term is only meaningful relative to the frame before it, both are rebuilt from the render
 * dimensions when those change, and both are broken by exactly the same events - a vanilla
 * frame, a lost frame, a replaced scene. Keeping them together is what makes "this frame is not
 * continuous with the last one" a single statement instead of two that can drift apart.
 *
 * The published values are read by the world projection and by the evaluation, which both have
 * to describe the *same* offset. The phase advances the sequence exactly once and publishes the
 * single value both of them read.
 */
class WorldPhaseState {
	private var jitter: DlssJitter? = null
	private var motion: DlssCameraMotion? = null

	/**
	 * Sub-pixel jitter for the current world phase, or null outside an eligible DLSS phase.
	 */
	var activeJitter: DlssJitterOffset? = null
		private set

	/**
	 * Camera-only motion for the current world phase, or null outside an eligible DLSS phase and
	 * for an eligible phase that was routed without a camera sample.
	 */
	var activeMotion: DlssFrameMotion? = null
		private set

	/** Whether the sequences exist yet, which they do only after a successful native startup. */
	val started: Boolean
		get() = jitter != null

	/**
	 * Rebuilds both sequences for a new render size: the jitter sequence whose length is the
	 * pixel ratio, and the motion reprojection whose scale is the render dimensions.
	 */
	fun rebuild(renderDimensions: DlssDimensions, outputDimensions: DlssDimensions) {
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
			jitter?.advance()
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

	/** Closes the phase. The sequences keep their accumulated state for the next frame. */
	fun close() {
		activeJitter = null
		activeMotion = null
	}

	/**
	 * Forgets the camera the next frame would reproject against.
	 *
	 * A frame that decided its route but never finished rendering still moved the predecessor
	 * forward. Nothing accumulated it, so the frame after it must not measure motion from a
	 * camera no image was ever produced for.
	 */
	fun resetMotion() {
		motion?.reset()
	}

	/**
	 * Forgets everything accumulated: the camera and the jitter phase both.
	 *
	 * Used when the scene itself is replaced rather than when one frame was lost. A world load or
	 * a dimension change can leave the camera exactly where it stood while every surface in the
	 * frame becomes a different one, so nothing the frames themselves carry distinguishes it from
	 * standing still - and the accumulated history it would keep describes a world that is gone.
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
