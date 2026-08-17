package me.snowmii.dlss.session

import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.FgMultiplier
import me.snowmii.streamline.FgState

/**
 * A [SessionBridge] that answers "nothing happened" to everything, for the tests that drive a
 * runtime seam without a native side behind it.
 *
 * Every method is the refusal a real bridge gives when its session cannot answer, so a test
 * overrides only the calls its own behaviour depends on and the rest stay visibly inert. Tests
 * that want the production shape - the READY gates, the latching - build a real
 * [LifecycleAdapter] over a [me.snowmii.streamline.NativeApi] double and pass that instead;
 * this exists for the cases where the session deliberately never reaches READY.
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
