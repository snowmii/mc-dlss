package me.snowmii.dlss.mixin;

import me.snowmii.dlss.mrt.HandVelocityWriterBindings;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Copies the hand submission bracket's slot onto every item submit record, the item-side
 * sibling of the model-submit copy: item quads are staged later, after the bracket is gone, so
 * the record itself must carry the slot through the batching boundary.
 */
@Mixin(ItemFeatureRenderer.Submit.class)
public class ItemFeatureRendererSubmitMotionMixin {
	@Inject(method = "<init>", at = @At("TAIL"))
	private void mcDlssBindHandIdentity(final CallbackInfo info) {
		HandVelocityWriterBindings.bindSubmit(this);
	}
}
