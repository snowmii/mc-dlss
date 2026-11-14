package me.snowmii.dlss.render.ui

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.renderpearl.api.commands.CommandEncoder
import org.joml.Vector4f

class UiTarget(
	private val allocate: (Int, Int) -> RenderTarget,
	private val release: (RenderTarget) -> Unit,
) : AutoCloseable {
	private var uiRenderTarget: RenderTarget? = null

	val currentUiTarget: RenderTarget?
		get() = uiRenderTarget

	fun acquireUiTarget(width: Int, height: Int): RenderTarget {
		val existing = uiRenderTarget
		if (existing != null && existing.width == width && existing.height == height) {
			return existing
		}
		releaseUiTarget()
		return allocate(width, height).also { uiRenderTarget = it }
	}

	fun clear(encoder: CommandEncoder) {
		val held = uiRenderTarget ?: return
		val color = held.colorTexture ?: return
		val depth = held.depthTexture ?: return
		encoder.clearColorAndDepthTextures(color, TRANSPARENT, depth, FAR_DEPTH)
	}

	override fun close() = releaseUiTarget()

	private fun releaseUiTarget() {
		uiRenderTarget?.let(release)
		uiRenderTarget = null
	}

	companion object {
		const val LABEL = "DLSS UI"

		val FORMAT = GpuFormat.RGBA8_UNORM

		private const val FAR_DEPTH = 0.0

		private val TRANSPARENT = Vector4f(0.0f, 0.0f, 0.0f, 0.0f)

		@JvmStatic
		fun forMinecraft(): UiTarget = UiTarget(
			allocate = { width, height -> TextureTarget(LABEL, width, height, FORMAT, GpuFormat.D32_FLOAT) },
			release = RenderTarget::destroyBuffers,
		)
	}
}
