package me.snowmii.dlss.mixin;

import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.render.WorldPhase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Breaks the DLSS history when Minecraft swaps the level out from under the renderer.
 *
 * DLSS accumulates across frames on the assumption that consecutive frames show the same world
 * seen from a camera that moved. A world load, a dimension change, and a disconnect all break that
 * assumption without necessarily moving the camera at all: the coordinates can be identical while
 * every surface in the frame is a different one. {@code DlssCameraMotion} catches the
 * discontinuities that move the camera; this catches the ones that do not.
 *
 * {@code setLevel} covers joining a world and every dimension change, both of which construct a new
 * {@code ClientLevel}; {@code clearClientLevel} covers leaving one. Neither creates the world phase
 * - {@code Minecraft.setLevel} runs on the client thread outside the render loop, and only
 * {@code LevelRenderer.render} is allowed to start the DLSS path - so a session that never rendered
 * a DLSS frame stays untouched.
 */
@Mixin(Minecraft.class)
public class MinecraftLevelChangeMixin {
	@Inject(method = "setLevel", at = @At("HEAD"))
	private void mcDlssResetHistoryOnLevelLoad(final ClientLevel level, final CallbackInfo info) {
		mcDlssResetHistory();
	}

	@Inject(method = "clearClientLevel", at = @At("HEAD"))
	private void mcDlssResetHistoryOnLevelClear(final Screen screen, final CallbackInfo info) {
		mcDlssResetHistory();
	}

	@Unique
	private void mcDlssResetHistory() {
		final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		if (phase != null) {
			phase.resetHistory();
		}
	}
}
