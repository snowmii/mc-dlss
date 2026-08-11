package me.snowmii.dlss.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.mrt.MotionVectorPipeline;
import me.snowmii.dlss.mrt.MotionVectorShader;
import me.snowmii.dlss.mrt.TerrainVelocityUniforms;
import me.snowmii.dlss.mrt.VelocityWriter;
import me.snowmii.dlss.render.DlssFrameMotion;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

import static me.snowmii.dlss.mrt.VelocityPipelineVariantKt.writerTwin;

/**
 * Adds the scene-sized velocity attachment to the terrain chunk passes, selects the terrain
 * velocity writer twin at the pipeline-boundary seam, and writes this frame's velocity
 * uniform block.
 *
 * {@code ChunkSectionsToRender.renderGroup} is the one method that draws both terrain groups -
 * OPAQUE (solid and cutout layers) and TRANSLUCENT - so it is the smallest seam that carries
 * the attachment, the clear lifecycle, and the pipeline selection together. While the DLSS
 * world phase is open on the velocity-MRT route and the scene target holds a velocity
 * companion, the pass is created with that RG16_FLOAT attachment at color index 1, the
 * attachment is cleared to the invalid sentinel before the opaque group draws (an encoder
 * command, never a pass clear: the descriptor's velocity attachment stays {@code
 * Optional.empty()} on both groups), and every pipeline bound into the pass is swapped for its
 * cached terrain writer twin, which is what lets the pass's two-attachment shape and the
 * pipeline's two-target shape agree while the twin's velocity fragment shader writes
 * jitter-stripped NDC camera motion into color target 1. The translucent group loads the
 * attachment instead of clearing it, so the opaque-written velocity survives through its
 * work. Outside the eligible phase or on the latched camera-only route, the velocity view is
 * null and both handlers fall through to the exact vanilla calls: the pass keeps one
 * attachment and the source pipeline binds unchanged, so nothing this mixin does can throw.
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

	/** One scene-lifetime buffer holding the terrain velocity uniform block, written per frame. */
	private static GpuBuffer VELOCITY_UNIFORM_BUFFER;

	/**
	 * Classifies every source pipeline {@code renderGroup} is about to bind, before the pass
	 * descriptor exists. Mirrors the pipelines the method itself selects - the wireframe
	 * debug override or each layer's own pipeline - so a first-encounter foreign terrain
	 * pipeline latches camera-only here, and the pass-creation handler below then reads a
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

	@WrapOperation(
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
		final OptionalDouble clearDepth,
		final Operation<RenderPass> original,
		// The enclosing renderGroup argument: the group decides whether the velocity attachment
		// clears (opaque) or loads (translucent).
		@Local(argsOnly = true) final ChunkSectionLayerGroup group
	) {
		final GpuTextureView velocity = mcDlssTerrainVelocityView();
		if (velocity == null) {
			return original.call(encoder, label, colorTexture, clearColor, depthTexture, clearDepth);
		}

		// The velocity clear lifecycle: the attachment is cleared to the invalid sentinel before
		// the opaque writer draws, so every pixel the opaque terrain group does not write - sky,
		// discarded cutout texels, the cleared far plane - reads invalid rather than stale motion
		// from an earlier frame, and the translucent group loads it, preserving the
		// opaque-written velocity through its work. The clear is an encoder command, never a
		// pass clear: the descriptor's velocity attachment stays Optional.empty() on both
		// groups, exactly as the attachment contract pins it.
		if (group == ChunkSectionLayerGroup.OPAQUE) {
			encoder.clearColorTexture(velocity.texture(), TerrainVelocityUniforms.SENTINEL);
		}

		// This frame's velocity uniform block: the published reprojection and reset flag, and
		// the velocity view's size for the shader's gl_FragCoord -> NDC reconstruction. Written
		// on the same encoder the pass is created from, so the copy executes in the same
		// submission as the draws that read the block.
		TerrainVelocityUniforms.writeFrame(encoder, velocityUniformBuffer(), mcDlssActiveMotion(), velocity);

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

	@WrapOperation(
		method = "renderGroup",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V")
	)
	private void mcDlssChunkSetPipeline(
		final RenderPass pass,
		final RenderPipeline pipeline,
		final Operation<Void> original
	) {
		if (VELOCITY_PASS.get() == pass) {
			// The terrain writer twin: the plain two-target velocity twin (which keeps the
			// source fragment shader and is the M-4 descriptor contract) with the mc-dlss
			// terrain velocity shader and its VelocityConfig uniform layout layered on, so the
			// pass writes jitter-stripped NDC camera motion into color target 1. The uniform
			// block was written by the pass-creation handler into the shared buffer this pass
			// binds, so every layer in the pass reads this frame's reprojection.
			pass.setUniform(TerrainVelocityUniforms.UNIFORM_NAME, VELOCITY_UNIFORM_BUFFER.slice());
			original.call(pass, writerTwin(pipeline, VelocityWriter.TERRAIN));
		} else {
			original.call(pass, pipeline);
		}
	}

	private static GpuTextureView mcDlssTerrainVelocityView() {
		final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		return phase == null ? null : phase.getTerrainVelocityView();
	}

	private static DlssFrameMotion mcDlssActiveMotion() {
		final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		return phase == null ? null : phase.getActiveMotion();
	}

	private static GpuBuffer velocityUniformBuffer() {
		if (VELOCITY_UNIFORM_BUFFER == null) {
			VELOCITY_UNIFORM_BUFFER = RenderSystem.getDevice().createBuffer(
				() -> "DLSS terrain velocity config",
				GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
				(long) TerrainVelocityUniforms.UBO_SIZE
			);
		}
		return VELOCITY_UNIFORM_BUFFER;
	}
}
