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
 * Hand UI window: {@code renderItemInHand} HEAD→TAIL. Screen effects / 3D crosshair run after
 * in {@code renderLevel} and stay on vanilla main. Read main at HEAD (redirect inactive).
 * Drawing is gated inside vanilla; a closed gate is an empty clear.
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
