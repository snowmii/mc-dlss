package me.snowmii.dlss.mixin;

import me.snowmii.dlss.mrt.EntityVelocityRender;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces supported main-target entity draws with the two-attachment velocity pass. */
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
		if (EntityVelocityRender.draw((PreparedRenderType)(Object)this, info)) {
			callback.cancel();
		}
	}
}
