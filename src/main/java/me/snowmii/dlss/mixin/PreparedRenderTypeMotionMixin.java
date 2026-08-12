package me.snowmii.dlss.mixin;

import me.snowmii.dlss.mrt.EntityVelocityRender;
import me.snowmii.dlss.mrt.HandVelocityRender;
import me.snowmii.dlss.mrt.MovingBlockVelocityRender;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces supported main-target entity, piston moving-block, and hand/item draws with the
 * two-attachment velocity pass. The breaking-block crumbling camera-motion writer is retired:
 * crumbling overlay draws keep the exact vanilla one-target route and their pixels stay sentinel
 * for the post-scene fill.
 */
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
		// identity in their own maps, so the entity and hand writers' predicates answer false for
		// them. The hand writer resolves its slot from its own maps, which entity and moving-block
		// draws never carry.
		if (MovingBlockVelocityRender.draw((PreparedRenderType)(Object)this, info) ||
			EntityVelocityRender.draw((PreparedRenderType)(Object)this, info) ||
			HandVelocityRender.draw((PreparedRenderType)(Object)this, info)) {
			callback.cancel();
		}
	}
}
