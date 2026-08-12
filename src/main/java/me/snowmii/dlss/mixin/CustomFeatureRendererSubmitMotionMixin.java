package me.snowmii.dlss.mixin;

import me.snowmii.dlss.mrt.HandVelocityWriterBindings;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Copies the hand submission bracket's slot onto every custom-geometry submit record, the
 * custom-geometry sibling of the item-submit copy: the map's custom geometry is staged later,
 * after the bracket is gone, so the record itself must carry the slot through the batching
 * boundary. The record type also keys the Map pose domain at staging time.
 */
@Mixin(CustomFeatureRenderer.Submit.class)
public class CustomFeatureRendererSubmitMotionMixin {
	@Inject(method = "<init>", at = @At("TAIL"))
	private void mcDlssBindHandIdentity(final CallbackInfo info) {
		HandVelocityWriterBindings.bindSubmit(this);
	}
}
