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
 * UI over HUD-less world. Aliased hudless/destination skips the unblended base copy
 * (sampling a target a pass writes is invalid). Distinct inputs run both passes.
 * Premultiplied: {@code ui.rgb + hudless.rgb * (1 - ui.a)}.
 */
class UiComposite(
	/** Injectable; production uses the render-loop sampler cache. */
	private val sampler: () -> GpuSampler = { RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST) },
) {
	/**
	 * Missing color view on any input: write nothing. Aliased hudless/destination: overlay only.
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
		/** Unblended copy. Aliased wiring skips this; destination already holds the world. */
		internal val HUDLESS_COPY_PIPELINE: RenderPipeline = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("mc-dlss", "pipeline/ui_composite_copy"))
			.withVertexShader("core/screenquad")
			.withFragmentShader("core/blit_screen")
			.withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.build()

		/** Premultiplied overlay: {@code BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA}. */
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
