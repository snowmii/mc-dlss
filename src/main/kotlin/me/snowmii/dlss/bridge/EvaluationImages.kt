package me.snowmii.dlss.bridge

/**
 * The two evaluation images the native bridge owns.
 *
 * DLSS writes its upscaled result somewhere Minecraft does not own, and reads camera motion
 * from an image Minecraft has no equivalent of, so both are allocated natively from the
 * configured dimensions: [motion] at render size, [output] at output size. The colour and depth
 * resources stay Minecraft's - they are the scene target the world already rendered into -
 * which is why only these two cross back over the ABI.
 *
 * They are reported so the caller can see what it is rendering through, and so releasing them
 * can be driven from above. They are *not* handed back in for an evaluation: the bridge reaches
 * its own images directly, which is why [EvaluationRequest] carries only the engine's two.
 */
data class DlssEvaluationImages(
	val motion: ImageBinding,
	val output: ImageBinding,
)
