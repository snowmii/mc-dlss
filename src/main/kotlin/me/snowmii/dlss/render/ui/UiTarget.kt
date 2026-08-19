package me.snowmii.dlss.render.ui

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.CommandEncoder
import org.joml.Vector4f

/**
 * Transparent full-resolution UI target. Output size, never DLSS render size. RGBA8 + depth
 * (overlay alpha; hand depth against UI). [clear] → transparent black + reversed-Z far plane.
 * Never owns two UI targets at once.
 */
class UiTarget(
	private val allocate: (Int, Int) -> RenderTarget,
	private val release: (RenderTarget) -> Unit,
) : AutoCloseable {
	private var uiRenderTarget: RenderTarget? = null

	val currentUiTarget: RenderTarget?
		get() = uiRenderTarget

	/**
	 * Allocate at [width]x[height] if missing or resized. Resize releases first so the mod
	 * never owns two UI targets.
	 */
	fun acquireUiTarget(width: Int, height: Int): RenderTarget {
		val existing = uiRenderTarget
		if (existing != null && existing.width == width && existing.height == height) {
			return existing
		}
		releaseUiTarget()
		return allocate(width, height).also { uiRenderTarget = it }
	}

	/** Transparent black + reversed-Z far. Missing color/depth: no-op. */
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

		/** RGBA8 so overlay alpha survives. */
		val FORMAT = GpuFormat.RGBA8_UNORM

		/** Reversed-Z: the cleared depth reads the far plane, matching the world's clear. */
		private const val FAR_DEPTH = 0.0

		private val TRANSPARENT = Vector4f(0.0f, 0.0f, 0.0f, 0.0f)

		/** Production: color + depth, vanilla usage flags, render-thread assert. */
		@JvmStatic
		fun forMinecraft(): UiTarget = UiTarget(
			allocate = { width, height -> TextureTarget(LABEL, width, height, true, FORMAT) },
			release = RenderTarget::destroyBuffers,
		)
	}
}
