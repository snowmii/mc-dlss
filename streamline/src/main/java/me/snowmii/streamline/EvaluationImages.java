package me.snowmii.streamline;

import java.util.Objects;

/**
 * The two evaluation images the native bridge owns.
 *
 * <p>DLSS writes its upscaled result somewhere Minecraft does not own, and reads camera motion
 * from an image Minecraft has no equivalent of, so both are allocated natively from the
 * configured dimensions: {@link #motion} at render size, {@link #output} at output size. The
 * colour and depth resources stay Minecraft's - they are the scene target the world already
 * rendered into - which is why only these two cross back over the ABI.
 *
 * <p>They are reported so the caller can see what it is rendering through, and so releasing
 * them can be driven from above. They are <em>not</em> handed back in for an evaluation: the
 * bridge reaches its own images directly, which is why {@link EvaluationRequest} carries only
 * the engine's two.
 */
public record EvaluationImages(
	ImageBinding motion,
	ImageBinding output
) {
	public EvaluationImages {
		Objects.requireNonNull(motion, "motion");
		Objects.requireNonNull(output, "output");
	}
}