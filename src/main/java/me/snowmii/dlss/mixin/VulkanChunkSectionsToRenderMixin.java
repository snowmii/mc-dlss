package me.snowmii.dlss.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.mrt.MotionVectorPipeline;
import me.snowmii.dlss.mrt.MotionVectorShader;
import me.snowmii.dlss.render.WorldPhase;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

import static me.snowmii.dlss.mrt.VelocityPipelineVariantKt.velocityTwin;

/**
 * Adds the scene-sized velocity attachment to the terrain chunk passes and selects the velocity
 * twin at the pipeline-boundary seam.
 *
 * {@code ChunkSectionsToRender.renderGroup} is the one method that draws both terrain groups -
 * OPAQUE (solid and cutout layers) and TRANSLUCENT - so it is the smallest seam that carries the
 * attachment and pipeline selection together. While the DLSS world phase is open on the
 * velocity-MRT route and the scene target holds a velocity companion, the pass is created with
 * that RG16_FLOAT attachment at color index 1 and every pipeline bound into it is swapped for
 * its cached velocity twin, which is what lets the pass's two-attachment shape and the
 * pipeline's two-target shape agree. Outside the eligible phase or on the latched camera-only
 * route, the velocity view is null and both redirects fall through to the exact vanilla calls:
 * the pass keeps one attachment and the source pipeline binds unchanged, so nothing this mixin
 * does can throw.
 *
 * The group's source pipelines are classified before the pass shape is chosen: a HEAD inject on
 * {@code renderGroup} observes every pipeline the group will bind through the session's
 * compatibility latch, so the first foreign pipeline flips the route to camera-only before the
 * pass descriptor exists. That ordering is what keeps the first foreign pipeline itself on
 * exact vanilla passthrough - one attachment, source pipeline bound unchanged - instead of only
 * the pipelines after it. Classification runs only when the pass would otherwise carry the
 * velocity attachment, so an unrelated foreign pipeline cannot flip the session latch on a frame
 * whose terrain passes are vanilla anyway. The lazy-compile seam keeps observing as a backstop
 * for anything the group classification cannot see.
 *
 * The twin is selected per pass rather than per pipeline because the attachment, not the
 * pipeline, is the boundary condition: a pass that carries the velocity attachment must bind a
 * twin (or vanilla {@code setPipeline} would throw on the attachment-count check), and a pass
 * without one must bind the source pipeline. The current pass is tracked per render thread -
 * only one render pass can be open at a time per command encoder - and the identity comparison
 * keeps a stale entry from one frame from affecting the next.
 */
@Mixin(ChunkSectionsToRender.class)
public class VulkanChunkSectionsToRenderMixin {
	private static final ThreadLocal<RenderPass> VELOCITY_PASS = new ThreadLocal<>();

	/**
	 * Classifies every source pipeline {@code renderGroup} is about to bind, before the pass
	 * descriptor exists. Mirrors the pipelines the method itself selects - the wireframe
	 * debug override or each layer's own pipeline - so a first-encounter foreign terrain
	 * pipeline latches camera-only here, and the pass-creation redirect below then reads a
	 * null velocity view and creates the exact vanilla one-attachment pass.
	 */
	@Inject(method = "renderGroup", at = @At("HEAD"))
	private void mcDlssClassifyTerrainPipelines(
		final ChunkSectionLayerGroup group,
		final GpuSampler sampler,
		final CallbackInfo ci
	) {
		// Only a pass that would carry the velocity attachment needs classification: a pass that
		// is already vanilla must not let an unrelated foreign pipeline flip the session latch.
		if (mcDlssTerrainVelocityView() == null) {
			return;
		}

		final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		if (phase == null) {
			return;
		}

		final boolean wireframe = SharedConstants.DEBUG_HOTKEYS && Minecraft.getInstance().wireframe;
		for (ChunkSectionLayer layer : group.layers()) {
			final RenderPipeline pipeline = wireframe ? RenderPipelines.WIREFRAME : layer.pipeline();
			phase.observePipeline(new MotionVectorPipeline(
				pipeline.getLocation().toString(),
				List.of(
					new MotionVectorShader(
						pipeline.getVertexShader().toString(),
						pipeline.getVertexShader().getNamespace()
					),
					new MotionVectorShader(
						pipeline.getFragmentShader().toString(),
						pipeline.getFragmentShader().getNamespace()
					)
				)
			));
		}
	}

	@Redirect(
		method = "renderGroup",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;"
		)
	)
	private RenderPass mcDlssChunkRenderPass(
		final CommandEncoder encoder,
		final Supplier<String> label,
		final GpuTextureView colorTexture,
		final Optional<Vector4fc> clearColor,
		final GpuTextureView depthTexture,
		final OptionalDouble clearDepth
	) {
		final GpuTextureView velocity = mcDlssTerrainVelocityView();
		if (velocity == null) {
			return encoder.createRenderPass(label, colorTexture, clearColor, depthTexture, clearDepth);
		}

		final RenderPassDescriptor descriptor = RenderPassDescriptor.create(label)
			.withColorAttachment(colorTexture, clearColor)
			.withColorAttachment(velocity, Optional.empty());
		if (depthTexture != null) {
			descriptor.withDepthAttachment(depthTexture, clearDepth);
		}

		descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, colorTexture.getWidth(0), colorTexture.getHeight(0)));
		final RenderPass pass = encoder.createRenderPass(descriptor);
		VELOCITY_PASS.set(pass);
		return pass;
	}

	@Redirect(
		method = "renderGroup",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V")
	)
	private void mcDlssChunkSetPipeline(final RenderPass pass, final RenderPipeline pipeline) {
		pass.setPipeline(VELOCITY_PASS.get() == pass ? velocityTwin(pipeline) : pipeline);
	}

	private static GpuTextureView mcDlssTerrainVelocityView() {
		final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		return phase == null ? null : phase.getTerrainVelocityView();
	}
}
