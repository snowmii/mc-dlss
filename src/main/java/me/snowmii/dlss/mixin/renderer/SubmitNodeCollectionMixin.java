package me.snowmii.dlss.mixin.renderer;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.render.WorldPhase;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * World-space nametag handling under DLSS.
 *
 * For non-sneaking entities, Minecraft submits both a normal nametag part (to solid/nameTags) and
 * a see-through part (to seeThrough). When DLSS is active, see-through text is redirected to render
 * at full native resolution in the UI target.
 *
 * This mixin:
 * 1. Suppresses the duplicate low-res normal submit inside sceneTarget so the upscaled world remains clean of blurry text.
 * 2. Ensures the native see-through text renders at full opaque white (0xFFFFFFFF) while preserving the translucent background box.
 */
@Mixin(SubmitNodeCollection.class)
public class SubmitNodeCollectionMixin {
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
			// Suppress low-res normal nametag in sceneTarget; see-through renders at native resolution in UiTarget
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
		if (phase != null && phase.isOpen() && color == -1) {
			// Render text at full opaque white in the native see-through pass
			return -1;
		}
		return original.call(opacity, color);
	}
}
