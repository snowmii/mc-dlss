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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Adds the scene-sized velocity attachment to the cloud pass, selects the cloud writer twin
 * at the pipeline-boundary seam, and guards the writer pass's close, while the DLSS world
 * phase is open on the velocity-MRT route.
 *
 * {@code CloudRenderer.render} is the one remaining bespoke world pass before the protected
 * hand seam: it creates one pass over the clouds target (or the main target without the
 * transparency chain) with {@code CLOUDS} or {@code FLAT_CLOUDS} and draws the CPU-baked cloud
 * cells through the {@code CloudFaces} texel buffer, the {@code CloudInfo}/
 * {@code DynamicTransforms} uniforms, and one QUADS index draw. The pass is created inline on
 * the shared command encoder, so the pass-creation handler is the seam that carries the
 * attachment, the payload write, and the pass shape together. While the phase offers the scene
 * velocity view, {@link CloudVelocityRender} runs its failure-atomic interception: the
 * preflight (payload computation, payload-buffer allocation, twin construction and device
 * precompile, payload write) happens before the two-attachment pass is constructed, and any
 * failure falls through to the exact vanilla one-attachment creation with the source pipeline
 * binding unchanged. When the preflight succeeded, the pass is created with the RG16_FLOAT
 * attachment at color index 1 and this frame's CloudVelocityConfig payload bound on the same
 * encoder (the cloud-offset drift composed into the camera reprojection, with the exact
 * invalid sentinel on a mesh rebuild, a clock discontinuity, or a frame without a
 * predecessor), and the pipeline bound into the pass is swapped for its cached cloud writer
 * twin - the two-target shape agrees with the pass, and the twin's velocity fragment shader
 * reproduces the source cloud color output byte-identically and writes NDC motion into color
 * target 1. The {@code CloudInfo}/{@code CloudFaces} binds, the fog and depth behavior, and
 * the draw count are all untouched: only the pass shape and the bound pipeline change. The
 * source render's try-with-resources close of the writer's pass runs through the owned close
 * seam, which absorbs a device-level close failure and drops the pass latch.
 *
 * The mesh-rebuild observation lives on the one {@code MappableRingBuffer.rotate} call inside
 * {@code render} - the rebuild block that regenerates the {@code CloudFaces} mesh after a cell
 * change, a camera-plane crossing, a cloud-status change, or a texture reload - and is cleared
 * at the head of every {@code render} call, so a rebuild invalidates exactly the frame it
 * happened on. The cloud clock (the game time and partial tick the render call derives
 * {@code cloudOffset} from) is read by the writer from the same sources
 * {@code LevelRenderer.render} passes down: the level's game time and
 * {@code Minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false)}.
 *
 * Outside the eligible phase or on the latched camera-only route, the velocity view is null
 * and every handler falls through to the exact vanilla calls: the pass keeps one attachment,
 * the source cloud pipeline binds unchanged, and the source close is untouched - so nothing
 * this mixin does can throw, on any route.
 */
@Mixin(CloudRenderer.class)
public class CloudRendererMotionMixin {
	private static final ThreadLocal<Boolean> CLOUD_MESH_REBUILT = ThreadLocal.withInitial(() -> false);

	/**
	 * Clears the mesh-rebuild observation before every render call, so a rebuild left over
	 * from a frame that drew no clouds (the rebuilt mesh had zero quads) can never invalidate
	 * a later frame's cloud velocity.
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
	 * The pass-creation interception, delegated to the writer's failure-atomic
	 * {@code createPass}: the writer preflights every fallible operation before the
	 * two-attachment pass exists and answers the exact vanilla one-attachment pass on any
	 * failure, so an eligible failure never throws and never leaves the source render with a
	 * pass the source pipeline cannot bind.
	 *
	 * ponytail: the writer owns the vanilla fallback, so this handler does not call the operation
	 * through. Give CloudVelocityRender's three seams an Operation (or a fallback lambda) if
	 * another mod ever needs to wrap these calls inside CloudRenderer.render.
	 */
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
		return CloudVelocityRender.createPass(
			encoder, label, colorTexture, clearColor, depthTexture, clearDepth, CLOUD_MESH_REBUILT.get()
		);
	}

	/**
	 * The pipeline-boundary handler: the source render call binds its {@code CLOUDS} or
	 * {@code FLAT_CLOUDS} selection exactly once into the cloud pass, and the writer swaps
	 * that selection for its cached two-target writer twin only when the pass-creation
	 * handler built the two-attachment shape and the preflight covered the selection. The
	 * twin keeps every source descriptor field, so the {@code CloudInfo}/{@code CloudFaces}
	 * uniforms the render call binds next resolve exactly as before; the CloudVelocityConfig
	 * block was bound by the pass-creation handler into the writer's shared buffer this pass
	 * reads, so the cloud draw reads this frame's reprojection. Every other pass or pipeline
	 * binds the source pipeline unchanged.
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
		CloudVelocityRender.bindPipeline(pass, pipeline);
	}

	/**
	 * The owned close seam: the source render's try-with-resources closes the writer's pass
	 * through the writer's guard, which absorbs a device-level close failure on the latched
	 * pass and drops the latch. Every other pass (including the vanilla fallback pass) closes
	 * exactly as the source render closes it.
	 */
	@WrapOperation(
		method = "render",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;close()V")
	)
	private void mcDlssCloudClose(final RenderPass pass, final Operation<Void> original) {
		CloudVelocityRender.closePass(pass);
	}
}
