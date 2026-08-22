package me.snowmii.dlss.mixin.renderer;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import me.snowmii.dlss.render.mrt.CloudVelocityRender;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cloud motion observation for 26.3.
 *
 * Minecraft 26.3 moved cloud drawing into the shared classic-transparency {@code RenderPass}
 * ({@code CloudRenderer.render(CloudStatus, RenderPass)}), so the 26.2 private two-attachment
 * cloud pass interception no longer exists. Mesh-rebuild tracking still works via
 * {@code prepare}/{@code MappableRingBuffer.rotate}. Twin pipeline resolution is kept on
 * {@code getCompiledPipeline} for when a dedicated cloud velocity pass is restored.
 */
@Mixin(CloudRenderer.class)
public class CloudRendererMotionMixin {
	@Unique
	private static final ThreadLocal<Boolean> CLOUD_MESH_REBUILT = ThreadLocal.withInitial(() -> false);

	@Inject(
		method = "prepare(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V",
		at = @At("HEAD")
	)
	private void mcDlssCloudPrepareHead(
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

	@WrapOperation(
		method = "prepare(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MappableRingBuffer;rotate()V")
	)
	private void mcDlssCloudMeshRebuilt(final MappableRingBuffer buffer, final Operation<Void> original) {
		CLOUD_MESH_REBUILT.set(true);
		original.call(buffer);
	}

	/**
	 * When a velocity-latched cloud pass exists, compile the writer twin instead of the source
	 * pipeline. With the shared translucent pass this is currently a no-op path (latch never
	 * set); kept so restoring a dedicated cloud pass only needs pass creation again.
	 */
	@WrapOperation(
		method = "render(Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;"
		)
	)
	private CompiledRenderPipeline mcDlssCloudCompiledPipeline(
		final RenderPipeline pipeline,
		final Operation<CompiledRenderPipeline> original
	) {
		return CloudVelocityRender.resolveCloudCompiledPipeline(pipeline, original::call);
	}
}
