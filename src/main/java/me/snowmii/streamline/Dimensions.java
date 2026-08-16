package me.snowmii.streamline;

/**
 * A pixel size, in the units the flat native ABI takes them.
 *
 * <p>Lives in the streamline package because the ABI is what constrains it: the native side is
 * told an output size and answers with a render size, and every layer above - the session's
 * configuration, the router's per-frame decision, the scene target's allocation - is
 * describing one of those two numbers. Defining it here is what lets the native bridge speak
 * in it without the lowest layer of the mod having to import the higher ones.
 */
public record Dimensions(
	/** Width in pixels. */
	int width,
	/** Height in pixels. */
	int height
) {
	public Dimensions {
		if (width <= 0) {
			throw new IllegalArgumentException("width must be positive");
		}
		if (height <= 0) {
			throw new IllegalArgumentException("height must be positive");
		}
	}

	/** One compact field for a diagnostic line, {@code width}x{@code height}. */
	@Override
	public String toString() {
		return width + "x" + height;
	}
}