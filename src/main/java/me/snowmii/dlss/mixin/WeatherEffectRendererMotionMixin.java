package me.snowmii.dlss.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTextureView;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.mrt.TerrainVelocityUniforms;
import me.snowmii.dlss.mrt.WeatherVelocityRender;
import me.snowmii.dlss.mrt.VelocityWriter;
import me.snowmii.dlss.render.WorldPhase;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

import static me.snowmii.dlss.mrt.VelocityPipelineVariantKt.writerTwin;

/**
 * Adds the scene-sized velocity attachment to the weather pass and selects the weather writer
 * twin at the pipeline-boundary seam, while the DLSS world phase is open on the velocity-MRT
 * route.
 *
 * {@code WeatherEffectRenderer.render} is the smallest remaining bespoke world pass: it
 * creates one pass over the weather target with {@code WEATHER_DEPTH_WRITE} or
 * {@code WEATHER_NO_DEPTH_WRITE} and draws the CPU-baked rain and snow columns. The pass is
 * created inline on a fresh command encoder, so the pass-creation handler is the seam that
 * carries the attachment, the payload write, and the pass shape together. While the phase
 * offers the scene velocity view, the pass is created with that RG16_FLOAT attachment at color
 * index 1, this frame's VelocityConfig payload is written on the same encoder (the terrain
 * writer's existing block: the jitter-stripped camera reprojection and the reset flag, which
 * forces the exact invalid sentinel), and the pipeline bound into the pass is swapped for its
 * cached weather writer twin - the two-target shape agrees with the pass, the twin's velocity
 * fragment shader reproduces the source particle color output byte-identically and writes
 * jitter-stripped NDC camera motion into color target 1. Rain and snow draws, the lightmap
 * bind, fog, and depth behavior are all untouched: only the pass shape and the bound pipeline
 * change.
 *
 * Outside the eligible phase or on the latched camera-only route, the velocity view is null
 * and both handlers fall through to the exact vanilla calls: the pass keeps one attachment
 * and the source weather pipeline binds unchanged, so nothing this mixin does can throw.
 */
@Mixin(WeatherEffectRenderer.class)
public class WeatherEffectRendererMotionMixin {
	private static final ThreadLocal<RenderPass> WEATHER_VELOCITY_PASS = new ThreadLocal<>();

	@WrapOperation(
		method = "render",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;"
		)
	)
	private RenderPass mcDlssWeatherRenderPass(
		final CommandEncoder encoder,
		final Supplier<String> label,
		final GpuTextureView colorTexture,
		final Optional<Vector4fc> clearColor,
		final GpuTextureView depthTexture,
		final OptionalDouble clearDepth,
		final Operation<RenderPass> original
	) {
		final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		final GpuTextureView velocity = phase == null ? null : phase.getTerrainVelocityView();
		if (velocity == null) {
			return original.call(encoder, label, colorTexture, clearColor, depthTexture, clearDepth);
		}

		// This frame's VelocityConfig payload: the published camera reprojection and reset
		// flag, and the velocity view's size for the shader's gl_FragCoord -> NDC
		// reconstruction. Written on the same encoder the pass is created from, so the copy
		// executes in the same submission as the draws that read the block.
		WeatherVelocityRender.writeFrame(encoder, phase, velocity);

		final RenderPassDescriptor descriptor = RenderPassDescriptor.create(label)
			.withColorAttachment(colorTexture, clearColor)
			.withColorAttachment(velocity, Optional.empty());
		if (depthTexture != null) {
			descriptor.withDepthAttachment(depthTexture, clearDepth);
		}

		descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, colorTexture.getWidth(0), colorTexture.getHeight(0)));
		final RenderPass pass = encoder.createRenderPass(descriptor);
		WEATHER_VELOCITY_PASS.set(pass);
		return pass;
	}

	@WrapOperation(
		method = "render",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V")
	)
	private void mcDlssWeatherSetPipeline(
		final RenderPass pass,
		final RenderPipeline pipeline,
		final Operation<Void> original
	) {
		if (WEATHER_VELOCITY_PASS.get() == pass) {
			// The weather writer twin: the plain two-target velocity twin (which keeps the
			// source fragment shader and is the M-4 descriptor contract) with the mc-dlss
			// weather velocity shader and the existing VelocityConfig uniform layout layered
			// on, so the pass writes jitter-stripped NDC camera motion into color target 1.
			// The uniform block was written by the pass-creation handler into the writer's
			// shared buffer this pass binds, so both weather draws read this frame's
			// reprojection.
			pass.setUniform(TerrainVelocityUniforms.UNIFORM_NAME, WeatherVelocityRender.uniformSlice());
			original.call(pass, writerTwin(pipeline, VelocityWriter.WEATHER));
		} else {
			original.call(pass, pipeline);
		}
	}
}
