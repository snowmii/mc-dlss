package me.snowmii.streamline;

import me.snowmii.dlss.api.ImageBinding;

/**
 * The engine target receiving the upscaled frame, in the units the flat native ABI takes.
 *
 * <p>This is Minecraft's own output-sized target - the one everything after the world phase
 * composes over - so the source of the copy is the bridge's output image and never appears
 * here. Optional view/format/usage metadata permits direct RR tone mapping; callers with
 * only an image handle retain the copy path. Usage is the actual Vulkan image usage.
 *
 * <p>{@link #outputDimensions} is stamped by the session adapter rather than supplied here,
 * for the same reason as in {@link EvaluationRequest}: it is the configured output size, and
 * it is carried so the bridge can refuse a destination that is not it.
 */
public record PresentTarget(
	long commandBuffer,
	long image,
	/** Stamped by the session adapter; see the class comment. */
	Dimensions outputDimensions,
    ImageBinding reflection,
    long imageView,
    int imageFormat,
    int imageUsage
) {
    public PresentTarget(long commandBuffer, long image, Dimensions dimensions, ImageBinding reflection) {
        this(commandBuffer, image, dimensions, reflection, 0L, 0, 0);
    }

    public PresentTarget(long commandBuffer, ImageBinding destination, Dimensions dimensions,
                         ImageBinding reflection, int imageUsage) {
        this(commandBuffer, destination.image(), dimensions, reflection,
            destination.view(), destination.format(), imageUsage);
    }

    public PresentTarget(long commandBuffer, long image, Dimensions dimensions) {
        this(commandBuffer, image, dimensions, null);
    }
	public PresentTarget() {
		this(0L, 0L, null);
	}

	/** Dimensions left null for the adapter to stamp. */
	public PresentTarget(long commandBuffer, long image) {
		this(commandBuffer, image, null);
	}
}
