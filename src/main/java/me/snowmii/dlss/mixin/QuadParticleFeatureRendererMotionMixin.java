package me.snowmii.dlss.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTextureView;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.mrt.ParticleVelocityRender;
import me.snowmii.dlss.mrt.TerrainVelocityUniforms;
import me.snowmii.dlss.render.WorldPhase;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

import static me.snowmii.dlss.mrt.VelocityPipelineVariantKt.particleVelocityTwin;
import static me.snowmii.dlss.mrt.VelocityPipelineVariantKt.velocityTwin;

/**
 * Adds the scene-sized velocity attachment to both particle passes and selects the particle
 * writer twin at the pipeline-boundary seam, while the DLSS world phase is open on the
 * velocity-MRT route.
 *
 * {@code QuadParticleFeatureRenderer.executeGroup} is the one method that draws both particle
 * families - the solid group into the scene (main) target, the translucent group into the
 * particles target - and the static {@code drawLayers} binds each layer's
 * {@code OPAQUE_PARTICLE} / {@code TRANSLUCENT_PARTICLE} pipeline. Both targets are
 * scene-sized on a DLSS frame, so the pass-creation redirect carries the attachment, the
 * payload write, and the pass shape together: while the phase offers the scene velocity view,
 * the pass is created with that RG16_FLOAT attachment at color index 1, this frame's
 * VelocityConfig payload is written on the same encoder (the terrain writer's existing block:
 * the jitter-stripped camera reprojection and the reset flag, which forces the exact invalid
 * sentinel), and the pipeline bound into the pass is swapped for its cached particle writer
 * twin - the two-target shape agrees with the pass, and the twin's swapped-in shader (the
 * existing particle-body velocity shader, which IS the vanilla {@code core/particle} body
 * verbatim plus the payload write) reproduces the source particle color output
 * byte-identically and writes jitter-stripped NDC camera motion into color target 1. Particle
 * draws, the lightmap bind, fog, depth, and target-zero blend behavior are all untouched: only
 * the pass shape and the bound pipeline change.
 *
 * Outside the eligible phase or on the latched camera-only route, the velocity view is null
 * and both redirects fall through to the exact vanilla calls: the pass keeps one attachment
 * and the source particle pipeline binds unchanged, so nothing this mixin does can throw.
 */
@Mixin(QuadParticleFeatureRenderer.class)
public class QuadParticleFeatureRendererMotionMixin {
	private static final ThreadLocal<RenderPass> PARTICLE_VELOCITY_PASS = new ThreadLocal<>();

	@Redirect(
		method = "executeGroup",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;"
		)
	)
	private RenderPass mcDlssParticleRenderPass(
		final CommandEncoder encoder,
		final Supplier<String> label,
		final GpuTextureView colorTexture,
		final Optional<Vector4fc> clearColor,
		final GpuTextureView depthTexture,
		final OptionalDouble clearDepth
	) {
		final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		final GpuTextureView velocity = phase == null ? null : phase.getTerrainVelocityView();
		if (velocity == null) {
			return encoder.createRenderPass(label, colorTexture, clearColor, depthTexture, clearDepth);
		}

		// This frame's VelocityConfig payload: the published camera reprojection and reset
		// flag, and the velocity view's size for the shader's gl_FragCoord -> NDC
		// reconstruction. Written on the same encoder the pass is created from, so the copy
		// executes in the same submission as the draws that read the block.
		ParticleVelocityRender.writeFrame(encoder, phase, velocity);

		final RenderPassDescriptor descriptor = RenderPassDescriptor.create(label)
			.withColorAttachment(colorTexture, clearColor)
			.withColorAttachment(velocity, Optional.empty());
		if (depthTexture != null) {
			descriptor.withDepthAttachment(depthTexture, clearDepth);
		}

		descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, colorTexture.getWidth(0), colorTexture.getHeight(0)));
		final RenderPass pass = encoder.createRenderPass(descriptor);
		PARTICLE_VELOCITY_PASS.set(pass);
		return pass;
	}

	/**
	 * The pipeline-boundary handler for the static {@code drawLayers}: the redirect handler
	 * must be static because its target method is, and it must carry the velocity write onto
	 * the pass exactly when the pass-creation redirect built the two-attachment shape.
	 */
	@Redirect(
		method = "drawLayers",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V")
	)
	private static void mcDlssParticleSetPipeline(final RenderPass pass, final RenderPipeline pipeline) {
		if (PARTICLE_VELOCITY_PASS.get() == pass) {
			// The particle writer twin: the plain two-target velocity twin (which keeps the
			// source fragment shader and is the M-4 descriptor contract) with the existing
			// particle-body velocity shader and the existing VelocityConfig uniform layout
			// layered on, so the pass writes jitter-stripped NDC camera motion into color
			// target 1. The uniform block was written by the pass-creation redirect into the
			// writer's shared buffer this pass binds, so every particle draw reads this
			// frame's reprojection.
			pass.setUniform(TerrainVelocityUniforms.UNIFORM_NAME, ParticleVelocityRender.uniformSlice());
			pass.setPipeline(particleVelocityTwin(velocityTwin(pipeline)));
		} else {
			pass.setPipeline(pipeline);
		}
	}
}
