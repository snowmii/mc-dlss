package me.snowmii.dlss.mixin;

import me.snowmii.dlss.mrt.HandVelocityWriterBindings;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Installs each item submit's hand slot while its pose-baked quads are staged, mirroring the
 * model-submit bracket. The item feature renderer stages quads in two passes (main, then foil);
 * each pass brackets the same submit, so the slot is installed exactly while that submit's
 * geometry is written.
 */
@Mixin(ItemFeatureRenderer.class)
public class ItemFeatureRendererMotionMixin {
	@Inject(method = "prepareSubmit", at = @At("HEAD"))
	private void mcDlssBeginItemSubmit(
		final ItemFeatureRenderer.Submit submit,
		final boolean foil,
		final CallbackInfo info
	) {
		HandVelocityWriterBindings.beginSubmit(submit);
	}

	@Inject(method = "prepareSubmit", at = @At("RETURN"))
	private void mcDlssEndItemSubmit(
		final ItemFeatureRenderer.Submit submit,
		final boolean foil,
		final CallbackInfo info
	) {
		HandVelocityWriterBindings.endSubmit();
	}
}
