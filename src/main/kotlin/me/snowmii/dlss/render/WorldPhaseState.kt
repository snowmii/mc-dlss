package me.snowmii.dlss.render

import com.mojang.blaze3d.pipeline.RenderTarget
import me.snowmii.streamline.Dimensions
import me.snowmii.dlss.mrt.ObjectMotionState

/**
 * The two sequences a DLSS frame accumulates against its predecessor, and the values this frame
 * published from them.
 *
 * Jitter, camera motion, and visible-object poses are one concern rather than three: all are
 * sequences whose current term is only meaningful relative to the frame before it, the camera
 * sequences are rebuilt from render dimensions when those change, and all three are broken by
 * exactly the same events - a vanilla frame, a lost frame, a replaced scene.
 * Keeping them together is what makes "this frame is not continuous with the last one" a single
 * statement instead of three that can drift apart.
 *
 * The published values are read by the world projection and by the evaluation, which both have
 * to describe the *same* offset. The phase advances the sequence exactly once and publishes the
 * single value both of them read.
 */
class WorldPhaseState {
	private var jitter: DlssJitter? = null
	private var motion: DlssCameraMotion? = null

	/**
	 * The previous-transform double buffer for visible-object poses, accumulated and broken
	 * with the jitter and camera-motion sequences.
	 *
	 * Each visible entity extraction captures the interpolated render position the geometry
	 * will be drawn at, keyed by the entity's stable id, into the frame in flight; successful
	 * world-phase [finish] is the frame boundary that publishes those captures exactly once;
	 * and every failed or skipped completion and every break - a vanilla frame, an abandoned
	 * phase, a replaced world, a release, a close - resets object history, so a reused entity id
	 * never inherits a dead object's predecessor.
	 */
	internal val objectMotion = ObjectMotionState()

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
			jitter?.advance()
		} else {
			jitter?.reset()
			// A vanilla frame renders no DLSS image and the object poses extracted for it were
			// never drawn into one, so they must not become anyone's predecessor.
			objectMotion.resetHistory()
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
	 * Finishes the phase and disposes its in-flight object captures from the outcome Minecraft
	 * displayed. Camera and jitter accumulation keep their existing close semantics; object
	 * poses publish only when DLSS evaluation/composition [completedDlssFrame], because a frame
	 * Streamline never produced must not become a dynamic writer's predecessor. Every false or
	 * skipped outcome resets instead, without an observable intermediate publication.
	 */
	fun finish(completedDlssFrame: Boolean) {
		activeJitter = null
		activeMotion = null
		if (completedDlssFrame) {
			objectMotion.publishFrame()
		} else {
			objectMotion.resetHistory()
		}
	}

	/**
	 * Forgets the camera the next frame would reproject against.
	 *
	 * A frame that decided its route but never finished rendering still moved the predecessor
	 * forward. Nothing accumulated it, so the frame after it must not measure motion from a
	 * camera no image was ever produced for - nor reproject an object against poses no image
	 * ever showed.
	 */
	fun resetMotion() {
		motion?.reset()
		objectMotion.resetHistory()
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
		objectMotion.resetHistory()
	}

	/** Drops the sequences entirely, for a runtime that is shutting down. */
	fun discard() {
		jitter = null
		motion = null
		objectMotion.resetHistory()
	}
}
