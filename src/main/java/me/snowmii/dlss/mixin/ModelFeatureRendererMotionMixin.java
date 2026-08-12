package me.snowmii.dlss.mixin;

import me.snowmii.dlss.mrt.EntityVelocityWriterBindings;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Installs each model submit's stable id while its pose-baked vertices are staged. */
@Mixin(ModelFeatureRenderer.class)
public class ModelFeatureRendererMotionMixin {
	@Inject(method = "prepareModel", at = @At("HEAD"))
	private void mcDlssBeginModelSubmit(
		final ModelFeatureRenderer.Submit<?> submit,
		final CallbackInfo info
	) {
		EntityVelocityWriterBindings.beginSubmit(submit);
	}

	@Inject(method = "prepareModel", at = @At("RETURN"))
	private void mcDlssEndModelSubmit(
		final ModelFeatureRenderer.Submit<?> submit,
		final CallbackInfo info
	) {
		EntityVelocityWriterBindings.endSubmit();
	}
}
