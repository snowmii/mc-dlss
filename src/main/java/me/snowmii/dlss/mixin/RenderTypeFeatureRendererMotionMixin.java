package me.snowmii.dlss.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import me.snowmii.dlss.mrt.EntityVelocityWriterBindings;
import me.snowmii.dlss.mrt.MovingBlockVelocityWriterBindings;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * One draw per entity/piston moving-block object before same-render-type consolidation.
 * Falling-block and ineligible submits keep vanilla merge.
 */
@Mixin(targets = "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group")
public class RenderTypeFeatureRendererMotionMixin {
	@Shadow private StagedVertexBuffer.@Nullable Draw lastDraw;
	@Shadow @Nullable private RenderType lastRenderType;

	@Inject(method = "getVertexBuilder", at = @At("HEAD"))
	private void mcDlssBeginEntityDraw(final RenderType renderType, final CallbackInfoReturnable<VertexConsumer> info) {
		// Exactly one of the two batching machines is active in any group - groups are per
		// feature renderer, so the fresh-boundary decisions are simply OR-ed for the shared
		// lastDraw/lastRenderType clear. The moving-block machine governs the reorder decision
		// from its own fresh boundary until the entity machine takes one, so a moving-block
		// history can never steer an entity group's decision and vice versa.
		final boolean entityBoundary = EntityVelocityWriterBindings.beginDraw(renderType);
		final boolean movingBoundary = MovingBlockVelocityWriterBindings.beginDraw(renderType);
		if (entityBoundary || movingBoundary) {
			// The normal fast path and the reorder lookup below both reuse one draw for a
			// consecutive render type. Clearing these identities starts a fresh draw; the list
			// lookup redirect also disables the reorder-side reuse.
			this.lastDraw = null;
			this.lastRenderType = null;
		}
		MovingBlockVelocityWriterBindings.updateGoverning(movingBoundary, entityBoundary);
	}

	@Inject(method = "getVertexBuilder", at = @At("RETURN"))
	private void mcDlssEndEntityDraw(final RenderType renderType, final CallbackInfoReturnable<VertexConsumer> info) {
		EntityVelocityWriterBindings.endDraw();
		MovingBlockVelocityWriterBindings.endDraw();
	}

	@WrapOperation(
		method = "getOrAddDraw",
		at = @At(value = "INVOKE", target = "Ljava/util/List;indexOf(Ljava/lang/Object;)I")
	)
	private int mcDlssDisableEntityReorder(
		final List<?> renderTypes,
		final Object preparedRenderType,
		final Operation<Integer> original
	) {
		// The governing marker set at the draw boundary picks the machine whose post-eligible
		// bookkeeping owns this group's reorder decision; with neither machine governing this is
		// exactly the pre-existing entity/vanilla decision.
		//
		// The operation is not called through - both writers reproduce the vanilla
		// List.indexOf themselves on their ineligible paths. Thread `original` into
		// consolidationIndex if another mod ever needs to wrap this JDK call.
		if (MovingBlockVelocityWriterBindings.isGoverning()) {
			return MovingBlockVelocityWriterBindings.consolidationIndex(renderTypes, preparedRenderType);
		}
		return EntityVelocityWriterBindings.consolidationIndex(renderTypes, preparedRenderType);
	}
}
