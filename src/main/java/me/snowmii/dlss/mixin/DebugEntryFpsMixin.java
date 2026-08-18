package me.snowmii.dlss.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.client.RuntimeControls;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugEntryFps;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Appends presented FPS next to vanilla's F3 fps line while frame generation is composing.
 */
@Mixin(DebugEntryFps.class)
public class DebugEntryFpsMixin {
	@ModifyExpressionValue(
		method = "display",
		at = @At(
			value = "INVOKE",
			target = "Ljava/lang/String;format(Ljava/util/Locale;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;"
		)
	)
	private String mcDlssAppendPresentedFps(final String line) {
		final RuntimeControls controls = ClientRuntime.active().activeControls();
		if (controls == null) {
			return line;
		}
		return line + controls.fgPresentedFpsSuffix(Minecraft.getInstance().getFps());
	}
}
