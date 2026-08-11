package me.snowmii.dlss.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import me.snowmii.dlss.mrt.EntityVelocityWriterBindings;
import me.snowmii.dlss.mrt.MovingBlockVelocityRender;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.blockentity.state.PistonHeadRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Brackets ordinary static block-entity model submission in a context that can never inherit an
 * adjacent entity identity - but only for the positive static renderer family.
 *
 * Block-entity model geometry is staged through the same ModelFeatureRenderer batching and
 * PreparedRenderType draw seam as entity geometry, so without a positive bracket the submit
 * records constructed inside a block-entity renderer call could associate with whatever identity
 * happened to be on the thread. The handler classifies the renderer first:
 * {@link EntityVelocityWriterBindings#isStaticBlockEntityRenderer} admits only the mapped
 * renderers whose submit geometry is time-invariant (the lectern book and the copper golem
 * statue). For those, a try/finally block-entity context brackets the renderer invocation:
 * submit records constructed inside it carry the block-entity marker instead of any entity id,
 * so the shared two-attachment velocity writer can draw the static geometry with a
 * zero-displacement camera reprojection while entity-id draws stay isolated.
 *
 * Every other block-entity renderer - animated banner, chest, enchantment table, shulker box,
 * bell, conduit, skull, spinning spawner display entity, moving piston head, signs, and all
 * special non-model renderers - is invoked exactly as vanilla invokes it, with no bracket and
 * no token, so its geometry keeps the original route and is never misclassified as static.
 *
 * Vanilla, CAMERA_ONLY, non-main output targets, and unsupported block-entity renderers keep
 * their exact source route: the bracket itself only installs a context, and eligibility is
 * decided later by the existing writer gates, which answer false for those paths without
 * throwing.
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
		// Positive static-family gate: only renderers whose submit geometry is time-invariant
		// open the block-entity bracket. Dynamic-capable renderers keep the exact source call
		// with no context and no token, so their geometry never becomes static block motion.
		if (!EntityVelocityWriterBindings.isStaticBlockEntityRenderer(renderer.getClass())) {
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
			return;
		}
		EntityVelocityWriterBindings.beginBlockEntity();
		try {
			original.call(renderer, state, poseStack, output, camera);
		} finally {
			EntityVelocityWriterBindings.endBlockEntity();
		}
	}
}
