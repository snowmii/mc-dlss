package me.snowmii.dlss.mixin;

import me.snowmii.dlss.client.DlssSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(OptionsSubScreen.class)
public class VideoSettingsScreenMixin {
	@Shadow
	protected @Nullable OptionsList list;

	@Inject(method = "addContents", at = @At("RETURN"))
	private void mcDlssAddSettingsButton(final CallbackInfo info) {
		if ((Object)this instanceof VideoSettingsScreen && list != null) {
			list.addBig(Button.builder(Component.translatable("mc-dlss.options.open"), button ->
				Minecraft.getInstance().gui.setScreen(new DlssSettingsScreen((Screen)(Object)this))
			).width(200).build());
		}
	}
}
