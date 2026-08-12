package me.snowmii.dlss.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import me.snowmii.dlss.mrt.MovingBlockVelocityRender;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.blockentity.state.PistonHeadRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Captures piston moving-block render states into the moving-block object-motion history at
 * the block-entity dispatcher seam.
 *
 * The static block-entity camera-motion writer is retired: ordinary block-entity renderers are
 * invoked exactly as vanilla invokes them, with no bracket and no identity token, so their
 * geometry keeps the exact source route and its pixels stay sentinel for the post-scene fill.
 *
 * What remains is the piston capture, the moving-block writer's seam: the mapped piston
 * renderer's render state carries the submitted moving-block states, their baked positions,
 * and the current piston offset. The capture seam binds each state to its block-position id
 * and records this frame's absolute position into the object-motion history before the submit
 * constructs the moving-block submits; with no eligible velocity phase it binds and captures
 * nothing, so vanilla, CAMERA_ONLY, and identity-less frames keep the exact source submit.
 * Every other block-entity renderer is invoked exactly as vanilla invokes it.
 */
@Mixin(net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher.class)
public class BlockEntityRenderDispatcherMotionMixin {
	@WrapOperation(
		method = "submit",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;submit(Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"
		)
	)
	@SuppressWarnings("rawtypes")
	private void mcDlssSubmitBlockEntity(
		final BlockEntityRenderer renderer,
		final BlockEntityRenderState state,
		final PoseStack poseStack,
		final SubmitNodeCollector output,
		final CameraRenderState camera,
		final Operation<Void> original
	) {
		// Piston moving-block gate: the mapped piston renderer's render state carries the
		// submitted moving-block states, their baked positions, and the current piston
		// offset. The capture seam binds each state to its block-position id and records
		// this frame's absolute position into the object-motion history before the submit
		// constructs the moving-block submits; with no eligible velocity phase it binds and
		// captures nothing, so vanilla, CAMERA_ONLY, and identity-less frames keep the exact
		// source submit.
		if (MovingBlockVelocityRender.isPistonHeadRenderer(renderer.getClass()) && state instanceof PistonHeadRenderState pistonState) {
			MovingBlockVelocityRender.capturePiston(pistonState);
		}
		original.call(renderer, state, poseStack, output, camera);
	}
}
