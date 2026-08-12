package me.snowmii.dlss.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.ui.UiPhase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Scopes the UI phase to {@code GuiRenderer.render}.
 *
 * The window opens only while a level is loaded: the main menu, the startup loading screen, and
 * panorama frames render through the same method into the vanilla main target and must stay
 * there, because nothing composites the UI target over them. The main target is read at HEAD,
 * while the redirect is still inactive, so the window always measures the real full-size target
 * and never sees its own override.
 *
 * The tail runs on every frame: it closes the GUI window - the frame's last UI window - and
 * bakes the frame's composite into the vanilla main target, so present, screenshots, and every
 * post-GUI consumer read the getter with the frame's UI already in the main target. A frame
 * whose head never opened the window (menu, no phase) has nothing to close or composite.
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
