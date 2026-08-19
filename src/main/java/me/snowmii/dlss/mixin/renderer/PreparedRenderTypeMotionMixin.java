package me.snowmii.dlss.mixin.renderer;

import me.snowmii.dlss.render.mrt.EntityVelocityRender;
import me.snowmii.dlss.render.mrt.MovingBlockVelocityRender;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces supported main-target entity and piston moving-block draws with the two-attachment
 * velocity pass. Crumbling overlay draws keep the vanilla one-target route; their pixels
 * stay sentinel for the post-scene fill.
 */
@Mixin(PreparedRenderType.class)
public class PreparedRenderTypeMotionMixin {
	@SuppressWarnings("ConstantConditions")
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
		// identity in their own maps, so the entity writer's predicate answers false for them.
		if (MovingBlockVelocityRender.draw((PreparedRenderType)(Object)this, info) ||
			EntityVelocityRender.draw((PreparedRenderType)(Object)this, info)) {
			callback.cancel();
		}
	}
}
