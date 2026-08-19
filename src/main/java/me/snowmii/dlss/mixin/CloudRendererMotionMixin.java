package me.snowmii.dlss.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import me.snowmii.dlss.mrt.CloudVelocityRender;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Cloud pass: velocity attachment + writer twin while velocity-MRT is open.
 * Preflight before the two-attachment pass; any failure falls through to vanilla
 * one-attachment. Mesh-rebuild observation is per-frame (cleared at render HEAD).
 */
@Mixin(CloudRenderer.class)
public class CloudRendererMotionMixin {
	@Unique
	private static final ThreadLocal<Boolean> CLOUD_MESH_REBUILT = ThreadLocal.withInitial(() -> false);

	/**
	 * Clears the mesh-rebuild flag at render HEAD so a rebuild on a zero-quad frame cannot
	 * invalidate the next one.
	 */
	@Inject(method = "render", at = @At("HEAD"))
	private void mcDlssCloudRenderHead(
		final int color,
		final CloudStatus cloudStatus,
		final float bottomY,
		final int range,
		final Vec3 cameraPosition,
		final long gameTime,
		final float partialTicks,
		final CallbackInfo info
	) {
		CLOUD_MESH_REBUILT.set(false);
	}

	/**
	 * Observes the mesh rebuild: the only {@code MappableRingBuffer.rotate} call inside
	 * {@code render} sits in the rebuild block, so this handler fires exactly when the
	 * {@code CloudFaces} mesh was regenerated this frame.
	 */
	@WrapOperation(
		method = "render",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MappableRingBuffer;rotate()V")
	)
	private void mcDlssCloudMeshRebuilt(final MappableRingBuffer buffer, final Operation<Void> original) {
		CLOUD_MESH_REBUILT.set(true);
		original.call(buffer);
	}

	/**
	 * Failure-atomic pass: preflight before the two-attachment pass exists. Any failure
	 * answers vanilla one-attachment. Does not call {@code original} — the writer owns fallback.
	 */
	@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
	@WrapOperation(
		method = "render",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;"
		)
	)
	private RenderPass mcDlssCloudRenderPass(
		final CommandEncoder encoder,
		final Supplier<String> label,
		final GpuTextureView colorTexture,
		final Optional<Vector4fc> clearColor,
		final GpuTextureView depthTexture,
		final OptionalDouble clearDepth,
		final Operation<RenderPass> original
	) {
		return CloudVelocityRender.createCloudVelocityPass(
			encoder, label, colorTexture, clearColor, depthTexture, clearDepth, CLOUD_MESH_REBUILT.get()
		);
	}

	/**
	 * Swap CLOUDS/FLAT_CLOUDS for the two-target twin only when pass-creation built that
	 * shape. Other binds stay source.
	 */
	@WrapOperation(
		method = "render",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V")
	)
	private void mcDlssCloudSetPipeline(
		final RenderPass pass,
		final RenderPipeline pipeline,
		final Operation<Void> original
	) {
		CloudVelocityRender.bindCloudPipeline(pass, pipeline);
	}

	/**
	 * Writer's close guard: absorbs a device-level close failure on the latched pass.
	 * Vanilla fallback pass closes as source.
	 */
	@WrapOperation(
		method = "render",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;close()V")
	)
	private void mcDlssCloudClose(final RenderPass pass, final Operation<Void> original) {
		CloudVelocityRender.closeCloudVelocityPass(pass);
	}
}
