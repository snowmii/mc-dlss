package me.snowmii.dlss.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.ui.UiPhase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Scopes the hand window of the UI phase to {@code GameRenderer.renderItemInHand}.
 *
 * The window opens at HEAD and closes at TAIL, so every hand/item draw inside the method - the
 * feature submission and execution - resolves {@code OutputTarget.MAIN_TARGET} to the
 * transparent full-resolution UI target. Screen effects and the 3D crosshair run later in
 * {@code GameRenderer.renderLevel}, after this method returns, so both stay on the vanilla main
 * target, and the depth clear before the hand reads GameRenderer's private field rather than
 * the getter. Hand drawing itself is gated inside the method on HUD visibility, camera type,
 * and game mode, so a window whose draw gate closed is just an empty clear.
 *
 * The main target is read at HEAD, while the world phase has already closed at the tail of
 * {@code LevelRenderer.render}, so the window always measures the real full-size target and
 * never sees its own override. The GUI window opens later at the head of {@code GuiRenderer.render}
 * on the same phase, which consumes the hand window's clear so the UI target clears once per
 * frame across both windows.
 */
@Mixin(GameRenderer.class)
public class GameRendererHandPhaseMixin {
	@Inject(method = "renderItemInHand", at = @At("HEAD"))
	private void mcDlssBeginHandPhase(final CallbackInfo info) {
		final UiPhase phase = ClientRuntime.renderLoop().uiPhase();
		if (phase == null) {
			return;
		}

		final RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		phase.beginHand(mainTarget);
	}

	@Inject(method = "renderItemInHand", at = @At("TAIL"))
	private void mcDlssEndHandPhase(final CallbackInfo info) {
		final UiPhase phase = ClientRuntime.active().activeUiPhase();
		if (phase != null) {
			phase.end();
		}
	}
}
