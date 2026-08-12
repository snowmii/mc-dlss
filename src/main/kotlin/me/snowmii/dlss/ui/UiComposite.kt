package me.snowmii.dlss.ui

import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.CommandEncoder
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuSampler
import java.util.Optional
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.resources.Identifier

/**
 * Writes the permanent UI composite: the transparent UI target over the HUD-less world into
 * the destination presentation consumes.
 *
 * Two full-screen passes at the destination's size:
 *
 *  1. the HUD-less world is copied into the destination unblended - it is the opaque base;
 *  2. the UI target is drawn over it with the premultiplied-alpha blend
 *     (`source = ONE`, `destination = ONE_MINUS_SRC_ALPHA`), so the result is
 *     `ui.rgb + hudless.rgb * (1 - ui.a)` and a pixel nothing drew on stays exactly the world.
 *
 * The frame wiring hands the same target in as the HUD-less source and the destination - the
 * world already lives in the destination - and a pass that samples the very target it writes
 * into is invalid on every backend, so the composite detects the alias and runs only the
 * overlay then: the base copy would be a redundant self-blit of contents the destination
 * already holds. Distinct inputs still compose through both passes.
 *
 * The inputs are the (ui, hudless, destination) boundary: any three targets compose, which is
 * what makes the seam injectable and verifiable off the render thread. Neither pass binds
 * default uniforms: both pipelines carry only the IN_SAMPLER layout the blit shader actually
 * reads.
 */
class UiComposite(
	/**
	 * The nearest-filter clamp sampler the passes sample their sources with. Injectable so the
	 * composite is drivable headless; production resolves the render loop's sampler cache.
	 */
	private val sampler: () -> GpuSampler = { RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST) },
) {
	/**
	 * Composes [ui] over [hudless] into [destination]. A missing color view on any input
	 * writes nothing: the destination is never partially composited.
	 *
	 * When [hudless] and [destination] share one color view, the base pass is skipped: the
	 * destination already holds the HUD-less world, and copying it into itself would sample
	 * the very target the pass renders into. The UI overlays the destination as it is.
	 */
	fun render(encoder: CommandEncoder, ui: RenderTarget, hudless: RenderTarget, destination: RenderTarget) {
		val uiColor = ui.colorTextureView ?: return
		val hudlessColor = hudless.colorTextureView ?: return
		val destinationColor = destination.colorTextureView ?: return

		val nearest = sampler()

		if (hudlessColor !== destinationColor) {
			encoder.createRenderPass({ "DLSS UI composite base" }, destinationColor, Optional.empty()).use { pass ->
				pass.setPipeline(HUDLESS_COPY_PIPELINE)
				pass.bindTexture("InSampler", hudlessColor, nearest)
				pass.draw(3, 1, 0, 0)
			}
		}
		encoder.createRenderPass({ "DLSS UI composite overlay" }, destinationColor, Optional.empty()).use { pass ->
			pass.setPipeline(UI_OVERLAY_PIPELINE)
			pass.bindTexture("InSampler", uiColor, nearest)
			pass.draw(3, 1, 0, 0)
		}
	}

	companion object {
		/**
		 * The unblended RGBA8 copy: replaces the destination with the HUD-less world exactly.
		 * Runs only when the HUD-less source is a target of its own; the aliased frame wiring
		 * skips it and keeps the world already in the destination.
		 *
		 * Same screen-quad and blit shaders as the vanilla blit pipelines, with only the
		 * IN_SAMPLER layout the shader reads, so the pass needs no default uniforms.
		 */
		internal val HUDLESS_COPY_PIPELINE: RenderPipeline = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("mc-dlss", "pipeline/ui_composite_copy"))
			.withVertexShader("core/screenquad")
			.withFragmentShader("core/blit_screen")
			.withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.build()

		/**
		 * The premultiplied-alpha overlay: full-write RGBA8 with
		 * [BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA] (source ONE, destination
		 * ONE_MINUS_SRC_ALPHA), the blend the transparent UI target is drawn with.
		 */
		internal val UI_OVERLAY_PIPELINE: RenderPipeline = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("mc-dlss", "pipeline/ui_composite_overlay"))
			.withVertexShader("core/screenquad")
			.withFragmentShader("core/blit_screen")
			.withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
			.withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.build()
	}
}
