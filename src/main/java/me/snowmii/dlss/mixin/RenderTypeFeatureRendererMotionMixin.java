package me.snowmii.dlss.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import me.snowmii.dlss.mrt.EntityVelocityWriterBindings;
import me.snowmii.dlss.mrt.HandVelocityWriterBindings;
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
 * Makes CPU-baked entity and piston moving-block geometry one draw per object before
 * RenderTypeFeatureRenderer's normal same-render-type consolidation can merge it. A draw-wide
 * object uniform is only correct after this boundary; non-entity, identity-less moving-block
 * (falling block), and ineligible submits keep vanilla consolidation untouched.
 */
@Mixin(targets = "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group")
public class RenderTypeFeatureRendererMotionMixin {
	@Shadow private StagedVertexBuffer.@Nullable Draw lastDraw;
	@Shadow @Nullable private RenderType lastRenderType;

	@Inject(method = "getVertexBuilder", at = @At("HEAD"))
	private void mcDlssBeginEntityDraw(final RenderType renderType, final CallbackInfoReturnable<VertexConsumer> info) {
		// Exactly one of the three batching machines is active in any group - groups are per
		// feature renderer, and the hand's window (inside GameRenderer.renderItemInHand) never
		// overlaps the level's entity or moving-block windows - so their fresh-boundary
		// decisions are simply OR-ed for the shared lastDraw/lastRenderType clear. The
		// moving-block machine governs the reorder decision from its own fresh boundary until
		// the entity machine takes one, so a moving-block history can never steer an entity
		// group's decision and vice versa.
		final boolean entityBoundary = EntityVelocityWriterBindings.beginDraw(renderType);
		final boolean movingBoundary = MovingBlockVelocityWriterBindings.beginDraw(renderType);
		final boolean handBoundary = HandVelocityWriterBindings.beginDraw(renderType);
		if (entityBoundary || movingBoundary || handBoundary) {
			// The normal fast path and the reorder lookup below both reuse one draw for a
			// consecutive render type. Clearing these identities starts a fresh draw; the list
			// lookup redirect also disables the reorder-side reuse.
			this.lastDraw = null;
			this.lastRenderType = null;
		}
		MovingBlockVelocityWriterBindings.setGoverning(
			movingBoundary || (MovingBlockVelocityWriterBindings.isGoverning() && !entityBoundary)
		);
	}

	@Inject(method = "getVertexBuilder", at = @At("RETURN"))
	private void mcDlssEndEntityDraw(final RenderType renderType, final CallbackInfoReturnable<VertexConsumer> info) {
		EntityVelocityWriterBindings.endDraw();
		MovingBlockVelocityWriterBindings.endDraw();
		HandVelocityWriterBindings.endDraw();
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
		// exactly the pre-existing entity/vanilla decision. The hand machine is consulted first:
		// its windows (the hand's own batch inside renderItemInHand) never overlap the level's
		// entity or moving-block windows, and while a hand submit is staged - or after an
		// eligible hand draw ended and an ineligible/foreign draw followed it - no reorder may
		// ever reuse a draw that carries a hand slot or an untagged foreign configuration.
		//
		// ponytail: the operation is not called through - both writers reproduce the vanilla
		// List.indexOf themselves on their ineligible paths. Thread `original` into
		// consolidationIndex if another mod ever needs to wrap this JDK call.
		if (HandVelocityWriterBindings.isActive() || HandVelocityWriterBindings.afterEligibleDraw()) {
			return HandVelocityWriterBindings.consolidationIndex(renderTypes, preparedRenderType);
		}
		if (MovingBlockVelocityWriterBindings.isGoverning()) {
			return MovingBlockVelocityWriterBindings.consolidationIndex(renderTypes, preparedRenderType);
		}
		return EntityVelocityWriterBindings.consolidationIndex(renderTypes, preparedRenderType);
	}
}
