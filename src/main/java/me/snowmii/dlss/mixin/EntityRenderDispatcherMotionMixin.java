package me.snowmii.dlss.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.mrt.EntityVelocityWriterBindings;
import me.snowmii.dlss.render.WorldPhase;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps the stable extracted entity id on the thread while that entity's CPU pose is submitted.
 *
 * ModelFeatureRenderer stages geometry after all entity submit calls return, so a dispatcher-local
 * HEAD/TAIL flag would not survive to the batching seam. Wrapping the renderer invocation gives
 * the whole submit call a try/finally identity bracket: ModelFeatureRenderer.Submit constructors
 * can copy the id, while flames/shadows and subsequent entities stay outside that association.
 */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMotionMixin {
	@WrapOperation(
		method = "submit",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"
		)
	)
	@SuppressWarnings("rawtypes")
	private void mcDlssSubmitWithEntityIdentity(
		final EntityRenderer renderer,
		final EntityRenderState state,
		final PoseStack poseStack,
		final SubmitNodeCollector output,
		final CameraRenderState camera,
		final Operation<Void> original
	) {
		final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		final Integer entityId = phase != null && phase.getEntityVelocityActive() ? phase.entityId(state) : null;
		EntityVelocityWriterBindings.beginEntity(entityId);
		try {
			original.call(renderer, state, poseStack, output, camera);
		} finally {
			EntityVelocityWriterBindings.endEntity();
		}
	}
}
