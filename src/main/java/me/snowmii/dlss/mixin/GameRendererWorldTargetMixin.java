package me.snowmii.dlss.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import me.snowmii.dlss.client.ActiveView;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.render.WorldPhase;
import me.snowmii.dlss.ui.UiPhase;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Override {@code GameRenderer.mainRenderTarget()} getter only (GameRenderer reads the field).
 * World phase wins over UI windows. Never initializes the runtime — only reads already-open
 * phases. Real frames never overlap those windows; precedence is defensive.
 */
@Mixin(GameRenderer.class)
public class GameRendererWorldTargetMixin {
	@Inject(method = "mainRenderTarget", at = @At("HEAD"), cancellable = true)
	private void mcDlssRedirectWorldTarget(final CallbackInfoReturnable<RenderTarget> info) {
		final ActiveView active = ClientRuntime.active();
		final WorldPhase worldPhase = active.activeWorldPhase();
		final UiPhase uiPhase = active.activeUiPhase();
		final RenderTarget override = ClientRuntime.resolveActiveTarget(
			worldPhase == null ? null : worldPhase.getWorldTargetOverride(),
			uiPhase == null ? null : uiPhase.getUiTargetOverride()
		);
		if (override != null) {
			info.setReturnValue(override);
		}
	}
}
