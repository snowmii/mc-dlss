package me.snowmii.dlss.mixin.renderer;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.render.WorldPhase;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SubmitNodeCollection.class)
public class SubmitNodeCollectionMixin {
	private static final int OPAQUE_WHITE = -1;

	@WrapOperation(
		method = "submitNameTag",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;submitNameTagPart(Lnet/minecraft/client/renderer/feature/TextFeatureRenderer$Submit;)V"
		)
	)
	private void mcDlssFilterNormalNameTag(
		final SubmitNodeCollection collection,
		final TextFeatureRenderer.Submit submit,
		final Operation<Void> original
	) {
		final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		if (phase != null && phase.isOpen()) {
			return;
		}
		original.call(collection, submit);
	}

	@WrapOperation(
		method = "submitNameTag",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/util/ARGB;color(FI)I"
		)
	)
	private int mcDlssCrispNameTagColor(
		final float opacity,
		final int color,
		final Operation<Integer> original
	) {
		final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		if (phase != null && phase.isOpen() && color == OPAQUE_WHITE) {
			return OPAQUE_WHITE;
		}
		return original.call(opacity, color);
	}
}
