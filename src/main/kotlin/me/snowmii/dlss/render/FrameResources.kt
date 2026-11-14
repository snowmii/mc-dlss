package me.snowmii.dlss.render

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.renderpearl.api.textures.GpuTextureView
import me.snowmii.dlss.DlssFrameRoute

class FrameResources(
	private val sceneTarget: SceneTarget,
	val frameEvaluation: FrameEvaluation?,
	private val quiesce: () -> Unit,
) : AutoCloseable {
	fun acquire(route: WorldTargetRoute): RenderTarget? {
		val releasesTarget = route.frame.route != DlssFrameRoute.DLSS ||
			sceneTarget.currentDimensions != route.worldDimensions
		if (sceneTarget.current != null && releasesTarget) {
			quiesce()
		}
		return sceneTarget.acquire(route)
	}

	fun release(releaseImages: Boolean) {
		if (sceneTarget.current != null || (releaseImages && frameEvaluation?.evaluationImages != null)) {
			quiesce()
		}
		if (releaseImages) {
			frameEvaluation?.close()
		}
		sceneTarget.close()
	}

	override fun close() {
		quiesce()
		release(releaseImages = true)
	}
}
