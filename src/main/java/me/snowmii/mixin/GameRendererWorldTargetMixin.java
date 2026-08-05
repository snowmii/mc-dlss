package me.snowmii.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import me.snowmii.dlss.DlssClientRuntime;
import me.snowmii.dlss.DlssWorldPhase;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Redirects the world phase to the mod's low-resolution scene target.
 *
 * {@code GameRenderer.mainRenderTarget()} is a plain getter, and GameRenderer's own uses read
 * the private field directly. Overriding the getter therefore reaches exactly the callers
 * outside GameRenderer - which, during an open world phase, are the LevelRenderer frame graph,
 * its screen-size derived targets, and the sky pass. Post chains, GUI clear, screenshots,
 * hand and item, screen effects, and presentation read the field and stay full-resolution.
 *
 * Never initializes the DLSS runtime: this runs on every frame from many call sites, so it only
 * reads a phase that {@code LevelRenderer.render} already opened.
 */
@Mixin(GameRenderer.class)
public class GameRendererWorldTargetMixin {
	@Inject(method = "mainRenderTarget", at = @At("HEAD"), cancellable = true)
	private void mcDlssRedirectWorldTarget(final CallbackInfoReturnable<RenderTarget> info) {
		final DlssWorldPhase phase = DlssClientRuntime.activeWorldPhase();
		if (phase == null) {
			return;
		}

		final RenderTarget override = phase.getWorldTargetOverride();
		if (override != null) {
			info.setReturnValue(override);
		}
	}
}
