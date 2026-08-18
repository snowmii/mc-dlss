package me.snowmii.dlss.mixin;

import me.snowmii.dlss.client.VideoOptionLocks;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Greys vanilla V-sync while frame generation is armed. The stored option is not written;
 * {@link OptionInstanceVideoLockMixin} also ignores {@code set} so Sodium's binding cannot
 * toggle it either.
 *
 * {@code list} lives on {@link OptionsSubScreen}, so this mixin extends that parent instead of
 * {@code @Shadow}ing a field {@code VideoSettingsScreen} does not declare.
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsVsyncLockMixin extends OptionsSubScreen {
	public VideoSettingsVsyncLockMixin(final Screen lastScreen, final Options options, final Component title) {
		super(lastScreen, options, title);
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void mcDlssLockVsyncWidget(final CallbackInfo info) {
		if (this.list == null) {
			return;
		}
		final AbstractWidget vsync = this.list.findOption(this.options.enableVsync());
		if (vsync != null) {
			vsync.active = !VideoOptionLocks.fgLocksVsync();
		}
	}
}
