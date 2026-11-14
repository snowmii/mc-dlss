package me.snowmii.dlss.streamline

import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.FgMultiplier
import me.snowmii.streamline.FgState
import me.snowmii.streamline.StreamlineSession
import me.snowmii.dlss.ReflexMode
import me.snowmii.dlss.SRMode
import me.snowmii.dlss.SRModelPreset

interface SessionBridge {
	fun reconfigure(qualityMode: SRMode, renderPreset: SRModelPreset): Dimensions?

	fun waitDeviceIdle(): Boolean

	fun waitFgInputsIdle(): Boolean

	fun queryFgState(): FgState?

	fun recordFrameGenerationOff(): Boolean

	fun queryFgMultiplier(): FgMultiplier?

	fun setFgMultiplier(numFramesToGenerate: Int): Boolean

	fun recordReflexFrameLimit(microsecondsPerFrame: Int): Boolean = false

	fun setReflexMode(mode: ReflexMode): Boolean = false

	fun queryReflexState(): StreamlineSession.ReflexStateInfo? = null
}
