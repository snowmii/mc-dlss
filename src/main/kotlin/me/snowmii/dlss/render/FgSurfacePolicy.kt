package me.snowmii.dlss.render

class FgSurfacePolicy(
	private val invalidateSurfaceConfiguration: () -> Unit = {},
) {
	private var frameGenerationActive = false
	private var frameSupported = true

	var recordFrameGenerationOff: () -> Unit = {}

	var numFramesToGenerate: Int = 1
		set(value) {
			field = maxOf(1, value)
		}

	val requiredSwapchainImages: Int
		get() = backBuffersFor(numFramesToGenerate)

	val effective: Boolean
		get() = frameGenerationActive && frameSupported

	val userEnabled: Boolean
		get() = frameGenerationActive

	fun setFrameGenerationActive(active: Boolean): Boolean {
		if (active == frameGenerationActive) {
			return false
		}
		frameGenerationActive = active
		invalidateSurfaceConfiguration()
		if (!active) {
			recordFrameGenerationOff()
		}
		return true
	}

	fun setCompositionSupported(supported: Boolean): Boolean {
		if (supported == frameSupported) {
			return false
		}
		frameSupported = supported
		if (!frameGenerationActive) {
			return false
		}
		if (!supported) {
			recordFrameGenerationOff()
		}
		return true
	}

	fun effectiveVsyncEnabled(stored: Boolean): Boolean =
		if (frameGenerationActive) false else stored

	fun minImageCount(vanilla: Int): Int =
		if (frameGenerationActive) maxOf(vanilla, requiredSwapchainImages) else vanilla

	companion object {
		const val PRESENT_HEADROOM = 2

		fun backBuffersFor(numFramesToGenerate: Int): Int =
			numFramesToGenerate + 1 + PRESENT_HEADROOM

		const val DEFAULT_DECLARED_BACK_BUFFERS = 4
	}
}
