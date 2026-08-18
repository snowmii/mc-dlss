package me.snowmii.dlss.mixin;

import me.snowmii.dlss.client.TextureFilteringPolicy;
import me.snowmii.dlss.client.VideoOptionLocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.TextureFilteringMethod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Live video-option locks. Stored values are not rewritten.
 *
 * While SR is on, a stored RGSS read becomes None so vanilla {@code UseRgss} and Sodium
 * {@code u_UseRGSS} stay off. While FG is armed, {@code enableVsync().set} is ignored so Sodium's
 * own vsync widget cannot toggle it either; vanilla greys the button in
 * {@link VideoSettingsVsyncLockMixin}.
 */
@Mixin(OptionInstance.class)
public class OptionInstanceVideoLockMixin {
	@Inject(method = "get", at = @At("RETURN"), cancellable = true)
	private void mcDlssEffectiveTextureFiltering(final CallbackInfoReturnable<Object> info) {
		if (!(info.getReturnValue() instanceof TextureFilteringMethod method)) {
			return;
		}
		final TextureFilteringMethod effective =
			TextureFilteringPolicy.effective(method, VideoOptionLocks.srLocksRgss());
		if (effective != method) {
			info.setReturnValue(effective);
		}
	}

	@Inject(method = "set", at = @At("HEAD"), cancellable = true)
	private void mcDlssLockVsyncSet(final Object value, final CallbackInfo info) {
		if (!(value instanceof Boolean) || !VideoOptionLocks.fgLocksVsync()) {
			return;
		}
		final Minecraft minecraft = Minecraft.getInstance();
		// Options.load() calls set() before Minecraft.options is assigned.
		if (minecraft == null || minecraft.options == null) {
			return;
		}
		if ((Object)this == minecraft.options.enableVsync()) {
			info.cancel();
		}
	}
}
