package me.snowmii.dlss.client

import me.snowmii.dlss.ReflexMode
import me.snowmii.dlss.api.RayReconstruction
import me.snowmii.dlss.render.FgSurfacePolicy
import me.snowmii.dlss.render.RenderRuntime
import me.snowmii.dlss.RRModelPreset
import me.snowmii.dlss.SRMode
import me.snowmii.dlss.SRModelPreset
import me.snowmii.dlss.readout.SessionReadout
import me.snowmii.streamline.StreamlineSession

class RuntimeControls(
	private val runtime: RenderRuntime,
	private val emitStatus: (String) -> Unit,
) {
	val surfacePolicy: FgSurfacePolicy
		get() = runtime.frameGeneration

	fun toggleFrameGeneration() = setFrameGenerationEnabled(!surfacePolicy.userEnabled)

	fun setFrameGenerationEnabled(enabled: Boolean) {
		val before = surfacePolicy.userEnabled
		val changed = runtime.setFrameGenerationEnabled(enabled)
		SessionReadout.emit(
			"DLSS fg toggle: armed $before -> ${surfacePolicy.userEnabled} " +
				"(requested=$enabled changed=$changed active=${surfacePolicy.effective} " +
				"multiplier=${runtime.fgMultiplier})",
		)
		ModConfig.user.frameGeneration = surfacePolicy.userEnabled
		emitStatus(readout())
	}

	fun toggleRayReconstruction() = setRayReconstructionEnabled(!RayReconstruction.enabled)

	fun setRayReconstructionEnabled(enabled: Boolean) {
		RayReconstruction.enabled = enabled
		ModConfig.user.rayReconstruction = enabled
		SessionReadout.emit("DLSS ray reconstruction: ${if (enabled) "on" else "off"}")
		emitStatus(readout())
	}

	fun cycleRrPreset() {
		val presets = RRModelPreset.entries
		val next = presets[(presets.indexOf(RayReconstruction.modelPreset) + 1) % presets.size]
		setRrModelPreset(next)
	}

	fun setRrModelPreset(preset: RRModelPreset) {
		RayReconstruction.modelPreset = preset
		ModConfig.user.rrModelPreset = preset
		SessionReadout.emit("DLSS RR model preset: ${preset.propertyValue}")
		emitStatus(readout())
	}

	fun cycleFgMultiplier() {
		runtime.cycleFgMultiplier()
		emitStatus(readout())
	}

	fun toggleEnabled() = setEnabled(!runtime.dlssEnabled)

	fun setEnabled(enabled: Boolean) {
		runtime.setDlssEnabled(enabled)
		ModConfig.user.enabled = runtime.dlssEnabled
		emitStatus(readout())
	}

	fun cycleQualityMode() {
		val modes = SRMode.entries
		val next = modes[(modes.indexOf(runtime.qualityMode) + 1) % modes.size]
		applyConfiguration(next, runtime.renderPreset)
	}

	fun cyclePreset() {
		val presets = SRModelPreset.entries
		val next = presets[(presets.indexOf(runtime.renderPreset) + 1) % presets.size]
		applyConfiguration(runtime.qualityMode, next)
	}

	val enabled: Boolean
		get() = runtime.dlssEnabled

	val qualityMode: SRMode
		get() = runtime.qualityMode

	val renderPreset: SRModelPreset
		get() = runtime.renderPreset

	val frameGenerationEnabled: Boolean
		get() = surfacePolicy.userEnabled

	val rayReconstructionEnabled: Boolean
		get() = RayReconstruction.enabled

	val rrModelPreset: RRModelPreset
		get() = RayReconstruction.modelPreset

	val frameGenerationMultiplier: Int
		get() = runtime.fgMultiplier + 1

	val reflexMode: ReflexMode
		get() = runtime.reflexMode

	val reflexState: StreamlineSession.ReflexStateInfo?
		get() = runtime.queryReflexState()

	fun setReflexMode(mode: ReflexMode) {
		runtime.setReflexMode(mode)
		emitStatus(readout())
	}

	fun cycleReflexMode() {
		val modes = ReflexMode.entries
		val next = modes[(modes.indexOf(runtime.reflexMode) + 1) % modes.size]
		setReflexMode(next)
	}

	fun readout(): String {
		val state = when {
			!runtime.config.enabled -> "disabled by ${ModConfig.ENABLED_PROPERTY}"
			!runtime.dlssEnabled -> "off"
			else -> runtime.sessionState.name.lowercase().replace('_', '-')
		}
		val internal = runtime.dlssRenderDimensions?.toString() ?: "not chosen yet"
		val rr = if (RayReconstruction.enabled) {
			"on at ${RayReconstruction.modelPreset.propertyValue}"
		} else {
			"off"
		}
		val reflex = runtime.reflexMode.propertyValue
		return SessionReadout.statusLine(
			state = state,
			fg = frameGenerationStatus(),
			multiplier = runtime.fgMultiplier + 1,
			mode = runtime.qualityMode,
			preset = runtime.renderPreset,
			internal = internal,
			output = runtime.outputDimensions,
			rr = rr,
			reflex = reflex,
		)
	}

	fun fgPresentedFpsSuffix(appFps: Int): String {
		if (!surfacePolicy.effective) {
			return ""
		}
		val fg = runtime.frameEvaluation?.sampleFgState() ?: return ""
		return SessionReadout.fgPresentedFpsSuffix(appFps, fg)
	}

	fun pacingLine(): String? = runtime.pacing.peek()?.removePrefix(", pacing=")

	fun gpuTimingLine(): String? = if (runtime.dlssEnabled) {
		runtime.frameEvaluation?.sampleTimings()?.let { "    DLSS GPU    : $it" }
	} else null

	private fun frameGenerationStatus(): String =
		SessionReadout.frameGenerationStatus(surfacePolicy.userEnabled, surfacePolicy.effective)

	private fun applyConfiguration(mode: SRMode, preset: SRModelPreset) {
		val applied = runtime.applyConfiguration(mode, preset)
		if (applied) {
			ModConfig.user.qualityMode = runtime.qualityMode
			ModConfig.user.renderPreset = runtime.renderPreset
			emitStatus(readout())
		} else {
			emitStatus(
				"DLSS kept ${runtime.qualityMode.propertyValue}/${runtime.renderPreset.propertyValue}; " +
					"${mode.propertyValue}/${preset.propertyValue} was refused. ${readout()}",
			)
		}
	}
}
