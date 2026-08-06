package me.snowmii.mixin;

import me.snowmii.dlss.DlssClientRuntime;
import me.snowmii.dlss.DlssRuntimeControls;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The three review keys: F6 toggles DLSS, F7 cycles the quality mode, F8 cycles the preset.
 *
 * Every acceptance criterion here is closed by a human watching one client, and two of them are
 * comparisons between DLSS on and DLSS off. Without a key, making that comparison means quitting,
 * editing a JVM property, and starting a second client, which compares two sessions rather than
 * one switch.
 *
 * The keys are fixed rather than registered as key mappings: the contract excludes keybinding
 * configuration and a settings GUI, and F6 through F8 are unbound in vanilla. Nothing here consumes
 * the event, so a key that later gains a vanilla meaning keeps it.
 *
 * This never creates the DLSS path. Key presses arrive on the client thread and can arrive before
 * the first world frame; only {@code LevelRenderer.render} is allowed to start the runtime, so a
 * press before that reaches nothing and reports nothing.
 */
@Mixin(KeyboardHandler.class)
public class KeyboardHandlerControlsMixin {
	/** GLFW_PRESS; a repeat or a release must not cycle a second time. */
	private static final int MC_DLSS_PRESS = 1;
	private static final int MC_DLSS_KEY_TOGGLE = 295; // GLFW_KEY_F6
	private static final int MC_DLSS_KEY_MODE = 296; // GLFW_KEY_F7
	private static final int MC_DLSS_KEY_PRESET = 297; // GLFW_KEY_F8

	@Inject(method = "keyPress", at = @At("HEAD"))
	private void mcDlssHandleReviewKeys(final long handle, final int action, final KeyEvent event, final CallbackInfo info) {
		if (action != MC_DLSS_PRESS) {
			return;
		}

		final DlssRuntimeControls controls = DlssClientRuntime.activeControls();
		if (controls == null) {
			return;
		}

		switch (event.key()) {
			case MC_DLSS_KEY_TOGGLE -> controls.toggleEnabled();
			case MC_DLSS_KEY_MODE -> controls.cycleQualityMode();
			case MC_DLSS_KEY_PRESET -> controls.cyclePreset();
			default -> {
			}
		}
	}
}
