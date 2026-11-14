package me.snowmii.dlss.render
import me.snowmii.streamline.Dimensions
import me.snowmii.dlss.DlssFrameRoute

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget

class SceneTarget(
	private val allocate: (Int, Int) -> RenderTarget,
	private val release: (RenderTarget) -> Unit,
) : AutoCloseable {
	private var target: RenderTarget? = null
	private var dimensions: Dimensions? = null

	val current: RenderTarget?
		get() = target

	val currentDimensions: Dimensions?
		get() = dimensions

	fun acquire(route: WorldTargetRoute): RenderTarget? {
		if (route.frame.route != DlssFrameRoute.DLSS) {
			releaseCurrent()
			return null
		}

		val wanted = route.worldDimensions
		val existing = target
		if (existing != null && dimensions == wanted) {
			return existing
		}

		releaseCurrent()
		val allocated = allocate(wanted.width, wanted.height)
		target = allocated
		dimensions = wanted
		return allocated
	}

	override fun close() = releaseCurrent()

	private fun releaseCurrent() {
		target?.let(release)
		target = null
		dimensions = null
	}

	companion object {
		const val LABEL = "DLSS Scene"

		@JvmStatic
		fun forMinecraft(): SceneTarget = SceneTarget(
			allocate = { width, height -> TextureTarget(LABEL, width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT) },
			release = RenderTarget::destroyBuffers,
		)
	}
}
