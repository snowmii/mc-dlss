package me.snowmii.dlss.client

import me.snowmii.McDlss
import me.snowmii.dlss.fg.FgSurfacePolicy
import me.snowmii.dlss.render.RenderRuntime
import me.snowmii.dlss.session.SRMode
import me.snowmii.dlss.session.SRModelPreset
import me.snowmii.dlss.readout.SessionReadout
import org.slf4j.LoggerFactory

/**
 * The reviewer's hands on the running DLSS session.
 *
 * These controls let one live session compare internal-resolution rendering with full-resolution
 * fallback without restarting the client.
 *
 * Fabric key mappings and the video-settings screen delegate here. User-facing choices persist in
 * [ModConfig.user]; launch properties remain explicit overrides for development and diagnostics.
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
	private val emitStatus: (String) -> Unit,
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
	fun toggleFrameGeneration() = setFrameGenerationEnabled(!surfacePolicy.userEnabled)

	fun setFrameGenerationEnabled(enabled: Boolean) {
		// The user's own mode, not the effective one: while composition is suspended - a pause, a
		// menu, an unhealthy plugin status - the effective mode reads off, and toggling against it
		// would ask for the mode the policy is already in and change nothing. Pressing the key
		// during a suspension has to switch the mode the user set, which is the armed one.
		val before = surfacePolicy.userEnabled
		val changed = runtime.setFrameGenerationEnabled(enabled)
		// DIAGNOSTIC: the F3 readout reports state, not whether the key reached this method. This
		// line separates a missed key event from a mode change the runtime refused.
		LOGGER.info(
			"DLSS fg toggle: armed {} -> {} (requested={} changed={} active={} multiplier={})",
			before,
			surfacePolicy.userEnabled,
			enabled,
			changed,
			surfacePolicy.effective,
			runtime.fgMultiplier,
		)
		ModConfig.user.frameGeneration = surfacePolicy.userEnabled
		emitStatus(readout())
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
		emitStatus(readout())
	}

	/** Switches DLSS off or back on, then reports what the frames after this one will be. */
	fun toggleEnabled() = setEnabled(!runtime.dlssEnabled)

	fun setEnabled(enabled: Boolean) {
		runtime.setDlssEnabled(enabled)
		ModConfig.user.enabled = runtime.dlssEnabled
		emitStatus(readout())
	}

	/** Moves to the next quality mode, sharpest to fastest, wrapping at the end. */
	fun cycleQualityMode() {
		val modes = SRMode.entries
		val next = modes[(modes.indexOf(runtime.qualityMode) + 1) % modes.size]
		applyConfiguration(next, runtime.renderPreset)
	}

	/** Moves to the next preset, leaving the quality mode alone. */
	fun cyclePreset() {
		val presets = SRModelPreset.entries
		val next = presets[(presets.indexOf(runtime.renderPreset) + 1) % presets.size]
		applyConfiguration(runtime.qualityMode, next)
	}

	/**
	 * One line naming the runtime state and dimensions that are not visible in the frame.
	 *
	 * NGX chooses internal resolution; no setting exposes it, and output-resolution frames look
	 * the same whether this route or native rendering produced them.
	 */
	val enabled: Boolean
		get() = runtime.dlssEnabled

	val qualityMode: SRMode
		get() = runtime.qualityMode

	val renderPreset: SRModelPreset
		get() = runtime.renderPreset

	val frameGenerationEnabled: Boolean
		get() = surfacePolicy.userEnabled

	val frameGenerationMultiplier: Int
		get() = runtime.fgMultiplier + 1

	fun readout(): String {
		val state = when {
			!runtime.config.enabled -> "disabled by ${ModConfig.ENABLED_PROPERTY}"
			!runtime.dlssEnabled -> "off"
			else -> runtime.sessionState.name.lowercase().replace('_', '-')
		}
		val internal = runtime.dlssRenderDimensions?.toString() ?: "not chosen yet"
		return "DLSS $state" +
			" | fg ${frameGenerationStatus()} at ${runtime.fgMultiplier + 1}x" +
			" | mode ${runtime.qualityMode.propertyValue}" +
			" | preset ${runtime.renderPreset.propertyValue}" +
			" | internal $internal" +
			" | output ${runtime.outputDimensions}"
	}

	fun fgPresentedFpsSuffix(appFps: Int): String {
		if (!surfacePolicy.effective) {
			return ""
		}
		val fg = runtime.frameEvaluation?.sampleFgState() ?: return ""
		return SessionReadout.fgPresentedFpsSuffix(appFps, fg)
	}

	fun motionProbeLine(): String? = runtime.frameEvaluation?.motionProbeLine()

	/**
	 * The FG half of the readout: the user's mode, and whether it is composing right now.
	 *
	 * User mode vs whether composition is running this frame. "off" is only the user's mode;
	 * a reconfigure suspends composition for a frame (cycling the multiplier invalidates the
	 * surface), so announcing inside that window must not report "off".
	 */
	private fun frameGenerationStatus(): String = when {
		!surfacePolicy.userEnabled -> "off"
		surfacePolicy.effective -> "on"
		else -> "on (suspended)"
	}

	private companion object {
		private val LOGGER = LoggerFactory.getLogger(McDlss.MOD_ID)
	}

	private fun applyConfiguration(mode: SRMode, preset: SRModelPreset) {
		val applied = runtime.applyConfiguration(mode, preset)
		if (applied) {
			ModConfig.user.qualityMode = runtime.qualityMode
			ModConfig.user.renderPreset = runtime.renderPreset
			emitStatus(readout())
		} else {
			// Naming the refusal matters more than naming the request: the session kept rendering,
			// and the reviewer needs to know the frames in front of them did not change.
			emitStatus(
				"DLSS kept ${runtime.qualityMode.propertyValue}/${runtime.renderPreset.propertyValue}; " +
					"${mode.propertyValue}/${preset.propertyValue} was refused. ${readout()}",
			)
		}
	}
}
