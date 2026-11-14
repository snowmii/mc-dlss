package me.snowmii.dlss.render

import com.mojang.blaze3d.pipeline.RenderTarget
import me.snowmii.dlss.api.RayReconstruction
import me.snowmii.streamline.Dimensions

class WorldPhaseState {
	private var jitter: DlssJitter? = null
	private var motion: DlssCameraMotion? = null

	var activeJitter: DlssJitterOffset? = null
		private set

	var activeMotion: DlssFrameMotion? = null
		private set

	val started: Boolean
		get() = jitter != null

	fun rebuild(renderDimensions: Dimensions, outputDimensions: Dimensions) {
		jitter = DlssJitter(renderDimensions, outputDimensions)
		motion = DlssCameraMotion(renderDimensions)
	}

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

	fun finish() {
		activeJitter = null
		activeMotion = null
	}

	fun resetMotion() {
		motion?.reset()
	}

	fun reset() {
		jitter?.reset()
		motion?.reset()
	}

	fun discard() {
		jitter = null
		motion = null
	}
}
