package me.snowmii.dlss.mixin.renderer;

import me.snowmii.dlss.render.mrt.EntityVelocityWriterBindings;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Copies the dispatcher-bracketed entity identity onto every model submit record. */
@Mixin(ModelFeatureRenderer.Submit.class)
public class ModelFeatureSubmitMotionMixin {
	@Inject(method = "<init>", at = @At("TAIL"))
	private void mcDlssBindEntityIdentity(final CallbackInfo info) {
		EntityVelocityWriterBindings.bindSubmit(this);
	}
}
