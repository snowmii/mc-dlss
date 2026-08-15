package me.snowmii.dlss.client
import me.snowmii.dlss.fg.FgSurfacePolicy
import me.snowmii.dlss.render.RenderRuntime
import me.snowmii.dlss.session.SRMode
import me.snowmii.dlss.session.SRModelPreset
import me.snowmii.dlss.config.ModConfig

/**
 * The reviewer's hands on the running DLSS session.
 *
 * Every acceptance criterion in this effort is closed by a human watching one client, and two of
 * them - "toggling DLSS changes internal scene resolution while output stays fixed" and "disabling
 * restores native full-resolution rendering" - are comparisons. Until this class existed the only
 * way to make that comparison was to quit, edit a JVM property, and start a second session, which
 * compares two clients rather than one switch.
 *
 * The controls are deliberately three keys and a chat line. Nothing here persists, because the
 * startup properties remain the source of what a session begins as, and nothing here has a screen,
 * because a settings GUI is outside the contract.
 *
 * Every action answers with the state that is actually in effect afterwards, not the one that was
 * asked for: a native side that refuses a mode change leaves the session rendering exactly as it
 * was, and a readout that claimed otherwise would be worse than no readout at all.
 *
 * Main-thread only. A change releases the low-resolution target and the native images, which are
 * GPU objects the render thread owns; Minecraft polls its GLFW key callbacks on the same thread it
 * renders on, so the release happens between frames rather than during one.
 */
class RuntimeControls(
	private val runtime: RenderRuntime,
	private val announce: (String) -> Unit,
) {
	/**
	 * The FG surface policy Minecraft's swapchain seams read, owned by the runtime so the
	 * policy's production invalidation wiring stays at the composition root.
	 */
	val surfacePolicy: FgSurfacePolicy
		get() = runtime.frameGeneration

	/**
	 * Switches FG off or back on. Every real transition recreates the swapchain through
	 * Minecraft's own reconfigure path, exactly once, so the frames that follow run under
	 * the non-FIFO and back-buffer policy the mode needs; switching off also re-records the
	 * DLSS-G options in the retained eOff mode exactly once on the transition, while the SR
	 * session and the UI split stay untouched. A user-off policy re-arms, and a re-arm of a
	 * status-latched policy stays refused - the readout reports the state actually in effect
	 * either way.
	 */
	fun toggleFrameGeneration() {
		runtime.setFrameGenerationEnabled(!surfacePolicy.active)
		announce(readout())
	}

	/**
	 * Cycles the FG multiplier 2x, 3x, ... up through the device ceiling and back to 2x,
	 * then reports the multiplier actually in effect.
	 *
	 * The runtime computes the next value from the bridge's own read of the stored
	 * multiplier and the device's numFramesToGenerateMax, so an unsupported multiplier is
	 * never offered; a refused record leaves the session on the multiplier it was already
	 * running and the readout says so.
	 */
	fun cycleFgMultiplier() {
		runtime.cycleFgMultiplier()
		announce(readout())
	}

	/** Switches DLSS off or back on, then reports what the frames after this one will be. */
	fun toggleEnabled() {
		runtime.setEnabled(!runtime.runtimeEnabled)
		announce(readout())
	}

	/** Moves to the next quality mode, sharpest to fastest, wrapping at the end. */
	fun cycleQualityMode() {
		val modes = SRMode.entries
		val next = modes[(modes.indexOf(runtime.qualityMode) + 1) % modes.size]
		// The preset follows the mode unless the reviewer had chosen one: a preset that is a
		// deliberate choice survives a mode change, and one that was only a default is replaced by
		// the new mode's default rather than carried into a mode it was never the default for.
		val preset = if (runtime.renderPreset == runtime.qualityMode.defaultPreset) {
			next.defaultPreset
		} else {
			runtime.renderPreset
		}
		apply(next, preset)
	}

	/** Moves to the next preset, leaving the quality mode alone. */
	fun cyclePreset() {
		val presets = SRModelPreset.entries
		val next = presets[(presets.indexOf(runtime.renderPreset) + 1) % presets.size]
		apply(runtime.qualityMode, next)
	}

	/**
	 * One line naming everything the reviewer cannot read off the screen.
	 *
	 * The internal resolution is the field AC-2 is decided by and the one nothing else reports:
	 * NGX chooses it, no setting shows it, and at output resolution the frame looks the same
	 * either way.
	 */
	fun readout(): String {
		val state = when {
			!runtime.config.enabled -> "disabled by ${ModConfig.ENABLED_PROPERTY}"
			!runtime.runtimeEnabled -> "off"
			else -> runtime.sessionState.name.lowercase().replace('_', '-')
		}
		val internal = runtime.renderDimensions?.toString() ?: "not chosen yet"
		return "DLSS $state" +
			" | fg ${if (surfacePolicy.active) "on" else "off"} at ${runtime.fgMultiplier + 1}x" +
			" | mode ${runtime.qualityMode.propertyValue}" +
			" | preset ${runtime.renderPreset.propertyValue}" +
			" | internal $internal" +
			" | output ${runtime.config.outputDimensions}"
	}

	private fun apply(mode: SRMode, preset: SRModelPreset) {
		val applied = runtime.applyConfiguration(mode, preset)
		if (applied) {
			announce(readout())
		} else {
			// Naming the refusal matters more than naming the request: the session kept rendering,
			// and the reviewer needs to know the frames in front of them did not change.
			announce("DLSS kept ${runtime.qualityMode.propertyValue}/${runtime.renderPreset.propertyValue}; ${mode.propertyValue}/${preset.propertyValue} was refused. ${readout()}")
		}
	}
}
