package me.snowmii.dlss

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import java.util.Optional

/**
 * Scopes one world render phase, which is the only window in which the mod's low-resolution
 * scene target stands in for Minecraft's main target.
 *
 * [DlssRenderRuntime] decides *whether* a frame is eligible and *what size* its target is.
 * This class decides *when* that target is visible to the renderer: the phase is opened at the
 * head of `LevelRenderer.render` and closed at its tail, and while it is open
 * [worldTargetOverride] is what `GameRenderer.mainRenderTarget()` answers. Everything outside
 * that window - post chains, GUI clear, screenshots, hand and item, screen effects, the 3D
 * crosshair, and presentation - keeps seeing the untouched full-size main target.
 *
 * Closing an eligible phase presents the scene color into the main target with a
 * nearest-neighbour full-screen blit. That blit is deliberately *not* an upscale: it is how the
 * low-resolution world becomes visible at all before DLSS evaluation exists, and its blockiness
 * is the point - it is the internal scene resolution, shown directly.
 *
 * Presentation and the sky-renderer reset are injected so the whole phase is verifiable off the
 * render thread; [forMinecraft] supplies the production wiring.
 */
class DlssWorldPhase(
	private val runtime: DlssRenderRuntime,
	private val present: (RenderTarget, RenderTarget) -> Unit,
	private val onWorldTargetChanged: () -> Unit,
) : AutoCloseable {
	private var scene: RenderTarget? = null
	private var mainTarget: RenderTarget? = null
	private var lastResolved: RenderTarget? = null

	/** True between [begin] and [end]. */
	var isOpen: Boolean = false
		private set

	/**
	 * Target `GameRenderer.mainRenderTarget()` must answer with, or null when the caller gets
	 * the vanilla main target. Non-null only inside an eligible DLSS world phase.
	 */
	val worldTargetOverride: RenderTarget?
		get() = if (isOpen) scene else null

	/**
	 * Opens the world phase against Minecraft's real main target and returns the target the
	 * world must render into: the low-resolution scene target for an eligible DLSS frame, or
	 * [mainTarget] itself for a vanilla frame.
	 */
	fun begin(normalInWorldFrame: Boolean, mainTarget: RenderTarget): RenderTarget {
		// An exception thrown inside LevelRenderer.render skips the tail that closes the phase.
		// Reopening then has to discard the abandoned frame rather than throw, because a stale
		// phase must not turn one render failure into a permanent one.
		discard()

		this.mainTarget = mainTarget
		scene = if (mainTarget.width > 0 && mainTarget.height > 0) {
			runtime.beginWorldPhase(normalInWorldFrame, DlssDimensions(mainTarget.width, mainTarget.height))
		} else {
			null
		}
		isOpen = true

		val resolved = scene ?: mainTarget
		if (resolved !== lastResolved) {
			// SkyRenderer caches the target it was built against and reuses it every frame.
			lastResolved = resolved
			onWorldTargetChanged()
		}
		return resolved
	}

	/**
	 * Closes the world phase, restoring the vanilla main target for the rest of the frame, then
	 * presents an eligible frame's low-resolution scene into it.
	 */
	fun end() {
		if (!isOpen) {
			return
		}

		val rendered = scene
		val destination = mainTarget
		isOpen = false
		scene = null
		mainTarget = null
		runtime.endWorldPhase()

		// Present only after the phase is closed, so the destination is the vanilla target.
		if (rendered != null && destination != null) {
			present(rendered, destination)
		}
	}

	override fun close() {
		discard()
		lastResolved = null
		runtime.close()
	}

	/** Drops an open phase without presenting it. The scene target itself stays owned by the runtime. */
	private fun discard() {
		if (!isOpen) {
			return
		}

		isOpen = false
		scene = null
		mainTarget = null
		runtime.endWorldPhase()
	}

	companion object {
		/** Production wiring: a real blit and a real sky-renderer reset. */
		@JvmStatic
		fun forMinecraft(runtime: DlssRenderRuntime): DlssWorldPhase = DlssWorldPhase(
			runtime = runtime,
			present = ::blitSceneToMainTarget,
			onWorldTargetChanged = {
				Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.shouldResetSkyRenderer = true
			},
		)

		/**
		 * Draws the scene color over the whole main target with the full-screen blit pipeline.
		 *
		 * `TRACY_BLIT` is the un-blended sibling of `ENTITY_OUTLINE_BLIT`: same screen-quad and
		 * blit shaders, no blend function, so the world replaces the main target's cleared color
		 * instead of compositing over it. The sampler is NEAREST on purpose - no filtering means
		 * the presented image shows the render resolution exactly as DLSS will receive it.
		 */
		private fun blitSceneToMainTarget(scene: RenderTarget, main: RenderTarget) {
			val source = scene.colorTextureView ?: return
			val destination = main.colorTextureView ?: return

			RenderSystem.getDevice()
				.createCommandEncoder()
				.createRenderPass({ "DLSS scene present" }, destination, Optional.empty())
				.use { pass ->
					RenderSystem.bindDefaultUniforms(pass)
					pass.setPipeline(RenderPipelines.TRACY_BLIT)
					pass.bindTexture(
						"InSampler",
						source,
						RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST),
					)
					pass.draw(3, 1, 0, 0)
				}
		}
	}
}
