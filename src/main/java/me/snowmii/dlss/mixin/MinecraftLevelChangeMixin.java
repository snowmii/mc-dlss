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
 * Reset DLSS history when Minecraft swaps the level. Coordinates can match while every
 * surface is new (world load, dimension change, disconnect). Camera-motion catch does not
 * cover that. Never creates the world phase.
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
