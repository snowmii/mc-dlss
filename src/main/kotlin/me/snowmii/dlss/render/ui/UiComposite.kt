package me.snowmii.dlss.render.ui

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.renderpearl.api.commands.CommandEncoder
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuSampler
import java.util.Optional
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.resources.Identifier

class UiComposite(
	private val sampler: () -> GpuSampler = { RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST) },
	private val pipelineResolver: (RenderPipeline) -> CompiledRenderPipeline = { pipeline ->
		RenderSystem.getCompiledPipeline(pipeline)
	},
) {
	fun render(encoder: CommandEncoder, ui: RenderTarget, hudless: RenderTarget, destination: RenderTarget) {
		val uiColor = ui.colorTextureView ?: return
		val hudlessColor = hudless.colorTextureView ?: return
		val destinationColor = destination.colorTextureView ?: return

		val nearest = sampler()

		if (hudlessColor !== destinationColor) {
			encoder.createRenderPass({ "DLSS UI composite base" }, destinationColor, Optional.empty()).use { pass ->
				pass.setPipeline(pipelineResolver(HUDLESS_COPY_PIPELINE))
				pass.setUniform("InSampler", hudlessColor, nearest)
				pass.draw(3, 1, 0, 0)
			}
		}
		encoder.createRenderPass({ "DLSS UI composite overlay" }, destinationColor, Optional.empty()).use { pass ->
			pass.setPipeline(pipelineResolver(UI_OVERLAY_PIPELINE))
			pass.setUniform("InSampler", uiColor, nearest)
			pass.draw(3, 1, 0, 0)
		}
	}

	companion object {
		internal val HUDLESS_COPY_PIPELINE: RenderPipeline = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("mc-dlss", "pipeline/ui_composite_copy"))
			.withVertexShader("core/screenquad")
			.withFragmentShader("core/blit_screen")
			.withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
			.withColorTargetState(ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.build()

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
