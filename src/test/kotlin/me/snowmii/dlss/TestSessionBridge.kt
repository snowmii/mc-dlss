package me.snowmii.dlss

import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.FgMultiplier
import me.snowmii.streamline.FgState
import me.snowmii.dlss.streamline.SessionBridge

/**
 * A [me.snowmii.dlss.streamline.SessionBridge] that answers "nothing happened" to everything. Every method is the
 * refusal a real bridge gives when its session cannot answer; a test overrides only the
 * calls its own behaviour depends on. Use this when the session deliberately never reaches
 * READY.
 */
open class TestSessionBridge : SessionBridge {
	override fun reconfigure(qualityMode: SRMode, renderPreset: SRModelPreset): Dimensions? = null

	override fun waitDeviceIdle(): Boolean = true

	override fun waitFgInputsIdle(): Boolean = true

	override fun queryFgState(): FgState? = null

	override fun recordFrameGenerationOff(): Boolean = false

	override fun queryFgMultiplier(): FgMultiplier? = null

	override fun setFgMultiplier(numFramesToGenerate: Int): Boolean = false
}
