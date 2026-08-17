package me.snowmii.dlss.ui

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.CommandEncoder
import org.joml.Vector4f

/**
 * Owns the mod's transparent full-resolution UI target.
 *
 * The permanent UI split renders every overlay - hand/item, chat, hotbar, tooltips, debug
 * screen, vignette - into this target and composites it over the HUD-less world afterwards.
 * Nothing here routes a draw or redirects a target: routing code owns those decisions, while
 * this class owns the target and its lifetime.
 *
 * The target is allocated at the output size - UI is drawn at display resolution, never at the
 * DLSS render resolution - in RGBA8_UNORM with depth, so the overlay alpha survives and the
 * hand can carry its own depth against the UI. It is transparent by construction: [clear]
 * resets the color to transparent black and the depth to the reversed-Z far plane at the start
 * of every frame, so a frame with nothing drawn on it composites the world untouched.
 *
 * Allocation and release are injected so the lifecycle is verifiable off the render thread;
 * [forMinecraft] supplies the production pair.
 */
class UiTarget(
	private val allocate: (Int, Int) -> RenderTarget,
	private val release: (RenderTarget) -> Unit,
) : AutoCloseable {
	private var uiRenderTarget: RenderTarget? = null

	val currentUiTarget: RenderTarget?
		get() = uiRenderTarget

	/**
	 * Returns the held UI target, allocating at [width]x[height] when nothing is held or the
	 * dimensions changed. A resize releases the held target before allocating the new one, so
	 * the mod never owns two UI targets at once.
	 */
	fun acquireUiTarget(width: Int, height: Int): RenderTarget {
		val existing = uiRenderTarget
		if (existing != null && existing.width == width && existing.height == height) {
			return existing
		}
		releaseUiTarget()
		return allocate(width, height).also { uiRenderTarget = it }
	}

	/**
	 * Empties the held target for the frame: color to transparent black, depth to the
	 * reversed-Z far plane. A target without a color or depth attachment is left untouched.
	 */
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

		/** The transparent full-resolution payload format: RGBA8 carries the overlay alpha. */
		val FORMAT = GpuFormat.RGBA8_UNORM

		/** Reversed-Z: the cleared depth reads the far plane, matching the world's clear. */
		private const val FAR_DEPTH = 0.0

		private val TRANSPARENT = Vector4f(0.0f, 0.0f, 0.0f, 0.0f)

		/**
		 * Production seam. [TextureTarget] allocates color plus depth with vanilla's usage
		 * flags and asserts the render thread, matching how the mod's scene target is built.
		 */
		@JvmStatic
		fun forMinecraft(): UiTarget = UiTarget(
			allocate = { width, height -> TextureTarget(LABEL, width, height, true, FORMAT) },
			release = RenderTarget::destroyBuffers,
		)
	}
}
