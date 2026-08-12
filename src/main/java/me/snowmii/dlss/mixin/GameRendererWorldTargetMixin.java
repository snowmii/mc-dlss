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
 * Routes the mod's three windows into {@code GameRenderer.mainRenderTarget()}.
 *
 * {@code GameRenderer.mainRenderTarget()} is a plain getter, and GameRenderer's own uses read
 * the private field directly. Overriding the getter therefore reaches exactly the callers
 * outside GameRenderer: during an open world phase, the LevelRenderer frame graph, its
 * screen-size derived targets, and the sky pass; during an open hand window, the hand and item
 * draw ranges inside {@code renderItemInHand}; during an open GUI window, the GuiRenderer
 * draw ranges. Post chains, GUI blur, screenshots, screen effects, the 3D crosshair, and
 * presentation read the field and stay full-resolution.
 *
 * The world phase wins over both UI windows, and outside all three windows the caller gets the
 * vanilla main target. The windows never overlap - the world phase closes at the tail of
 * `LevelRenderer.render`, the hand window closes at the tail of `renderItemInHand`, and the GUI
 * window opens long afterwards at the head of `GuiRenderer.render` - so the precedence is
 * defensive.
 *
 * Never initializes the DLSS runtime: this runs on every frame from many call sites, so it only
 * reads phases that `LevelRenderer.render`, `renderItemInHand`, and `GuiRenderer.render`
 * already opened.
 */
@Mixin(GameRenderer.class)
public class GameRendererWorldTargetMixin {
	@Inject(method = "mainRenderTarget", at = @At("HEAD"), cancellable = true)
	private void mcDlssRedirectWorldTarget(final CallbackInfoReturnable<RenderTarget> info) {
		final ActiveView active = ClientRuntime.active();
		final WorldPhase worldPhase = active.activeWorldPhase();
		final UiPhase uiPhase = active.activeUiPhase();
		final RenderTarget override = ClientRuntime.resolveTargetOverride(
			worldPhase == null ? null : worldPhase.getWorldTargetOverride(),
			uiPhase == null ? null : uiPhase.getUiTargetOverride()
		);
		if (override != null) {
			info.setReturnValue(override);
		}
	}
}
