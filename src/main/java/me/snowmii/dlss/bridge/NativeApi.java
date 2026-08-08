package me.snowmii.dlss.bridge;

import java.nio.file.Path;
import java.util.List;

/**
 * Native lifecycle calls exposed to the session adapter and test doubles.
 *
 * <p>The per-frame recording calls take a request object rather than a long argument list,
 * because that is the shape the flat ABI itself now takes and because the alternative was a
 * signature in which two adjacent {@code long} handles could be transposed by either side
 * without any diagnostic. The request types are defined in this package for the same reason
 * this interface is: they are the vocabulary of the ABI, not of the renderer that fills them.
 */
public interface NativeApi {
	/** Flat ABI success result defined by native/mc_dlss.h. */
	int SUCCESS_RESULT = 1;

	int initialize(
		long vkInstance,
		long vkPhysicalDevice,
		long vkDevice,
		Path sdkPath,
		Path dataPath
	);

	DlssDimensions queryOptimalDimensions(
		int outputWidth,
		int outputHeight,
		int qualityMode
	);

	/**
	 * Stores the dimensions, the NGX performance/quality mode, and the render preset the next
	 * feature creation uses.
	 *
	 * <p>{@code renderPreset} is an {@code NVSDK_NGX_DLSS_Hint_Render_Preset} value. It is written
	 * onto the capability parameters before the feature is created, because NGX reads the hint
	 * at creation and ignores it afterwards.
	 *
	 * <p>These dimensions are what everything else is sized from: the images {@link
	 * #acquireImages()} allocates, the feature the next evaluation creates, and the sizes the
	 * recording calls check their callers against.
	 */
	int configure(
		int outputWidth,
		int outputHeight,
		int renderWidth,
		int renderHeight,
		int qualityMode,
		int renderPreset
	);

	/**
	 * Returns the native-owned motion and output images for the configured dimensions,
	 * creating them on first use and reusing them while that configuration holds.
	 *
	 * <p>Reported so the caller can see what it is rendering through and drive their release.
	 * They are never passed back in for an evaluation - the bridge reaches its own images
	 * directly.
	 */
	DlssEvaluationImages acquireImages();

	int releaseImages();

	/**
	 * Blocks until the Vulkan device has finished everything submitted to it.
	 *
	 * <p>Called before releasing anything the recorded frames referenced - the engine's
	 * low-resolution render target as much as the native images - because Minecraft's Vulkan
	 * backend keeps frames in flight and freeing a resource one of them still reads loses the
	 * device.
	 */
	int waitDeviceIdle();

	/**
	 * GPU timings of the last frame that completed all three recorded stages, or null when none
	 * has yet or the device cannot timestamp graphics work.
	 *
	 * <p>Never waits on the GPU: the result describes a frame several frames old.
	 */
	DlssFrameTimings frameTimings();

	/**
	 * Records the camera-only motion pass that fills the native motion image from the engine's
	 * depth image, on the caller's command buffer.
	 *
	 * <p>This has to precede {@link #evaluate} on the same buffer: the evaluation reads the image
	 * this pass writes, and the pass ends with the barrier that makes those writes visible.
	 */
	int writeMotion(MotionRequest request);

	/**
	 * Records the copy of the upscaled output image into an engine target, on the caller's
	 * command buffer.
	 *
	 * <p>This has to follow {@link #evaluate} on the same buffer: it copies the image the
	 * evaluation writes, and the destination is what the rest of the frame composes over.
	 */
	int presentOutput(PresentTarget target);

	/**
	 * Records the DLSS evaluation on the caller's command buffer.
	 *
	 * <p>The request carries only the engine's colour and depth. The motion and output images
	 * are the bridge's own, allocated by {@link #acquireImages()} from the configured
	 * dimensions, so handing them back would be the caller returning handles the bridge already
	 * holds.
	 */
	int evaluate(EvaluationRequest request);

	/**
	 * The deduplicated Vulkan 1.2 feature names Streamline's loaded features (DLSS, DLSS-G,
	 * Reflex) require the device to enable, as {@code slGetFeatureRequirements} reports them
	 * through {@code mc_dlss_query_device_feature_12}.
	 *
	 * <p>Default-implemented so the pre-SL test doubles that stand in for the bridge do not
	 * have to declare a query they never reach; {@link Native} overrides it.
	 */
	default List<String> queryDeviceFeatures12() {
		throw new UnsupportedOperationException("queryDeviceFeatures12");
	}

	/**
	 * The deduplicated Vulkan 1.3 feature names Streamline's loaded features require, as
	 * {@code slGetFeatureRequirements} reports them through {@code mc_dlss_query_device_feature_13}.
	 *
	 * <p>Default-implemented for the same reason as {@link #queryDeviceFeatures12()}.
	 */
	default List<String> queryDeviceFeatures13() {
		throw new UnsupportedOperationException("queryDeviceFeatures13");
	}

	/**
	 * The extra Vulkan queues Streamline's loaded features require the host to create, summed
	 * across features as {@code slGetFeatureRequirements} reports them through
	 * {@code mc_dlss_query_queue_requirements}.
	 *
	 * <p>Default-implemented for the same reason as {@link #queryDeviceFeatures12()}.
	 */
	default SlQueueRequirements queryQueueRequirements() {
		throw new UnsupportedOperationException("queryQueueRequirements");
	}
}
