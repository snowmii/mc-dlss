package me.snowmii.dlss.mixin;

import me.snowmii.dlss.mrt.EntityVelocityWriterBindings;
import me.snowmii.dlss.mrt.HandVelocityRender;
import me.snowmii.dlss.mrt.HandVelocityWriterBindings;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Installs each model submit's stable id or hand slot while its pose-baked vertices are staged. */
@Mixin(ModelFeatureRenderer.class)
public class ModelFeatureRendererMotionMixin {
	@Inject(method = "prepareModel", at = @At("HEAD"))
	private void mcDlssBeginModelSubmit(
		final ModelFeatureRenderer.Submit<?> submit,
		final CallbackInfo info
	) {
		// The bare player arm renders through this same model seam (the empty-hand and map
		// branches), carrying the hand bracket's slot; its baked submit pose and model part are
		// the arm pose history's capture. Entity model submits carry no hand slot and are
		// ignored.
		HandVelocityRender.captureStagedHandPose(submit);
		EntityVelocityWriterBindings.beginSubmit(submit);
		HandVelocityWriterBindings.beginSubmit(submit);
	}

	@Inject(method = "prepareModel", at = @At("RETURN"))
	private void mcDlssEndModelSubmit(
		final ModelFeatureRenderer.Submit<?> submit,
		final CallbackInfo info
	) {
		EntityVelocityWriterBindings.endSubmit();
		HandVelocityWriterBindings.endSubmit();
	}
}
