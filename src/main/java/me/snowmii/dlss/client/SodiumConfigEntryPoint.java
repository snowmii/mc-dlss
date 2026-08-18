package me.snowmii.dlss.client;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Native Sodium page linking to the shared DLSS settings screen. */
public final class SodiumConfigEntryPoint implements ConfigEntryPoint {
	@Override
	public void registerConfigLate(final ConfigBuilder builder) {
		builder.registerOwnModOptions().addPage(
			builder.createExternalPage()
				.setName(Component.translatable("mc-dlss.options.title"))
				.setScreenConsumer(parent -> Minecraft.getInstance().gui.setScreen(new DlssSettingsScreen(parent)))
		);
	}
}
