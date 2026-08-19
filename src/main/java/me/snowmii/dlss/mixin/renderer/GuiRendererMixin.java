package me.snowmii.dlss.mixin.renderer;

import com.mojang.blaze3d.pipeline.RenderTarget;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.render.ui.UiPhase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GUI UI window: HEAD only if {@code level != null} (menu/panorama stay on vanilla main).
 * TAIL always closes and composites. Read main at HEAD while the redirect is still inactive.
 */
@Mixin(GuiRenderer.class)
public class GuiRendererMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void mcDlssBeginUiPhase(final CallbackInfo info) {
		if (Minecraft.getInstance().level == null) {
			return;
		}

		final UiPhase phase = ClientRuntime.renderLoop().uiPhase();
		if (phase == null) {
			return;
		}

		final RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		phase.begin(mainTarget);
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void mcDlssEndUiPhase(final CallbackInfo info) {
		final UiPhase phase = ClientRuntime.active().activeUiPhase();
		if (phase != null) {
			phase.endFrame();
		}
	}
}
