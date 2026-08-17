package me.snowmii.dlss.mixin;

import me.snowmii.dlss.client.ChatReadout;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.client.RuntimeControls;
import me.snowmii.dlss.pass.StressRuntime;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The review keys: F6 toggles DLSS, F7 cycles the quality mode, F8 cycles the preset, F9
 * toggles the GPU stress pass the DLSS comparison is measured under, F10 toggles frame
 * generation, recreating the swapchain on every transition, and F12 cycles the FG
 * multiplier 2x through the device ceiling and back.
 *
 * The controls let one client compare DLSS and fallback without restarting or editing JVM
 * properties.
 *
 * The keys are fixed rather than registered as key mappings: the contract excludes keybinding
 * configuration and a settings GUI, and F6 through F8 plus F10 and F12 are unbound in vanilla.
 * Nothing here consumes
 * the event, so a key that later gains a vanilla meaning keeps it.
 *
 * This never creates the DLSS path. Key presses arrive on the client thread and can arrive before
 * the first world frame; only {@code LevelRenderer.render} is allowed to start the runtime, so a
 * press before that reaches nothing and reports nothing.
 */
@Mixin(KeyboardHandler.class)
public class KeyboardHandlerControlsMixin {
	@Unique
	private static final Logger LOGGER = LoggerFactory.getLogger("mc-dlss");
	/** GLFW_PRESS; a repeat or a release must not cycle a second time. */
	@Unique
	private static final int MC_DLSS_PRESS = 1;
	@Unique
	private static final int MC_DLSS_KEY_TOGGLE = 295; // GLFW_KEY_F6
	@Unique
	private static final int MC_DLSS_KEY_MODE = 296; // GLFW_KEY_F7
	@Unique
	private static final int MC_DLSS_KEY_PRESET = 297; // GLFW_KEY_F8
	@Unique
	private static final int MC_DLSS_KEY_STRESS = 298; // GLFW_KEY_F9
	@Unique
	private static final int MC_DLSS_KEY_FG = 299; // GLFW_KEY_F10
	@Unique
	private static final int MC_DLSS_KEY_FG_MULTIPLIER = 301; // GLFW_KEY_F12

	@Inject(method = "keyPress", at = @At("HEAD"))
	private void mcDlssHandleReviewKeys(final long handle, final int action, final KeyEvent event, final CallbackInfo info) {
		if (action != MC_DLSS_PRESS) {
			return;
		}

		// Handled before the DLSS controls, and separately from them: the scene passes run in
		// sessions where DLSS never started, and those are exactly the sessions whose frame rate
		// the loaded DLSS ones are compared against.
		if (event.key() == MC_DLSS_KEY_STRESS) {
			final String readout = StressRuntime.togglePasses();
			if (readout != null) {
				ChatReadout.send(readout);
			}
			return;
		}

		// DIAGNOSTIC: a review key that never reaches the switch and one whose action is refused
		// are indistinguishable from chat, and F10 in particular is a key Windows itself claims.
		// Logging every key in the review range separates "GLFW never delivered it" from
		// "delivered, dispatched, and the action declined".
		if (event.key() >= MC_DLSS_KEY_TOGGLE && event.key() <= MC_DLSS_KEY_FG_MULTIPLIER) {
			LOGGER.info("DLSS review key: {}", event.key());
		}

		final RuntimeControls controls = ClientRuntime.active().activeControls();
		if (controls == null) {
			return;
		}

		switch (event.key()) {
			case MC_DLSS_KEY_TOGGLE -> controls.toggleEnabled();
			case MC_DLSS_KEY_MODE -> controls.cycleQualityMode();
			case MC_DLSS_KEY_PRESET -> controls.cyclePreset();
			case MC_DLSS_KEY_FG -> controls.toggleFrameGeneration();
			case MC_DLSS_KEY_FG_MULTIPLIER -> controls.cycleFgMultiplier();
			default -> {
			}
		}
	}
}
