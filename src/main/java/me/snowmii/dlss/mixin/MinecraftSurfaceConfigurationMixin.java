package me.snowmii.dlss.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.client.RuntimeControls;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * FG on: Minecraft's reconfigure must not pick FIFO. Policy ({@code FgSurfacePolicy}) answers
 * false for vsync while FG is armed so the block takes {@code IMMEDIATE}/{@code MAILBOX}
 * first. Stored vsync is never written, so it survives FG on/off. No controls → stored value.
 */
@Mixin(Minecraft.class)
public class MinecraftSurfaceConfigurationMixin {
	@ModifyExpressionValue(
		method = "renderFrame",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;"
		)
	)
	private Object mcDlssFgVsyncRead(final Object original) {
		final RuntimeControls controls = ClientRuntime.active().activeControls();
		if (controls == null) {
			return original;
		}
		return controls.getSurfacePolicy().effectiveVsyncEnabled((Boolean) original);
	}
}
