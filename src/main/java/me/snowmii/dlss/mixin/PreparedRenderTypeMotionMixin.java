package me.snowmii.dlss.mixin;

import me.snowmii.dlss.mrt.BreakingBlockVelocityRender;
import me.snowmii.dlss.mrt.EntityVelocityRender;
import me.snowmii.dlss.mrt.MovingBlockVelocityRender;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces supported main-target entity, piston moving-block, and breaking-block crumbling draws with the two-attachment velocity pass. */
@Mixin(PreparedRenderType.class)
public class PreparedRenderTypeMotionMixin {
	@Inject(
		method = "drawFromBuffer(Lnet/minecraft/client/renderer/StagedVertexBuffer$ExecuteInfo;)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void mcDlssDrawEntityVelocity(
		final StagedVertexBuffer.ExecuteInfo info,
		final CallbackInfo callback
	) {
		// The moving-block writer is checked first: its draws carry the packed long block-position
		// identity in their own maps, so the entity writer's predicates answer false for them.
		// The crumbling writer is checked last: its pipeline (core/rendertype_crumbling) shares
		// nothing with the entity or block-shaped families, so the predicates never overlap.
		if (MovingBlockVelocityRender.draw((PreparedRenderType)(Object)this, info) ||
			EntityVelocityRender.draw((PreparedRenderType)(Object)this, info) ||
			BreakingBlockVelocityRender.draw((PreparedRenderType)(Object)this, info)) {
			callback.cancel();
		}
	}
}
