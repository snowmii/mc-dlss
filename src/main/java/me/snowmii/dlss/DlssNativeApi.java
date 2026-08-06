package me.snowmii.dlss;

import java.nio.file.Path;

/** Native lifecycle calls exposed to the session adapter and test doubles. */
public interface DlssNativeApi {
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
	 * {@code renderPreset} is an {@code NVSDK_NGX_DLSS_Hint_Render_Preset} value. It is written
	 * onto the capability parameters before the feature is created, because NGX reads the hint
	 * at creation and ignores it afterwards.
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
	 */
	DlssEvaluationImages acquireImages();

	int releaseImages();

	/**
	 * Blocks until the Vulkan device has finished everything submitted to it.
	 *
	 * Called before releasing anything the recorded frames referenced - the engine's low-resolution
	 * render target as much as the native images - because Minecraft's Vulkan backend keeps frames
	 * in flight and freeing a resource one of them still reads loses the device.
	 */
	int waitDeviceIdle();

	/**
	 * GPU timings of the last frame that completed all three recorded stages, or null when none
	 * has yet or the device cannot timestamp graphics work.
	 *
	 * Never waits on the GPU: the result describes a frame several frames old.
	 */
	DlssFrameTimings frameTimings();

	/**
	 * Records the camera-only motion pass on the caller's command buffer, filling the
	 * native motion image from the engine's depth image.
	 *
	 * {@code reprojection} is the 16 column-major floats of {@code DlssFrameMotion.reprojection},
	 * which maps a jittered clip position to the previous frame's unjittered one.
	 */
	int writeMotion(
		long commandBuffer,
		long depthView,
		long depthImage,
		int depthFormat,
		int depthAspectMask,
		int depthBaseMipLevel,
		int depthLevelCount,
		int depthBaseArrayLayer,
		int depthLayerCount,
		float[] reprojection,
		int renderWidth,
		int renderHeight
	);

	/**
	 * Records the copy of the upscaled output image into an engine target, on the caller's
	 * command buffer.
	 *
	 * This has to follow {@link #evaluate} on the same buffer: it copies the image the
	 * evaluation writes, and the destination is what the rest of the frame composes over.
	 */
	int presentOutput(
		long commandBuffer,
		long destinationImage,
		int destinationAspectMask,
		int destinationBaseMipLevel,
		int destinationLevelCount,
		int destinationBaseArrayLayer,
		int destinationLayerCount,
		int destinationWidth,
		int destinationHeight
	);

	int evaluate(
		long commandBuffer,
		long colorView,
		long colorImage,
		int colorFormat,
		int colorAspectMask,
		int colorBaseMipLevel,
		int colorLevelCount,
		int colorBaseArrayLayer,
		int colorLayerCount,
		long depthView,
		long depthImage,
		int depthFormat,
		int depthAspectMask,
		int depthBaseMipLevel,
		int depthLevelCount,
		int depthBaseArrayLayer,
		int depthLayerCount,
		long motionView,
		long motionImage,
		int motionFormat,
		int motionAspectMask,
		int motionBaseMipLevel,
		int motionLevelCount,
		int motionBaseArrayLayer,
		int motionLayerCount,
		long outputView,
		long outputImage,
		int outputFormat,
		int outputAspectMask,
		int outputBaseMipLevel,
		int outputLevelCount,
		int outputBaseArrayLayer,
		int outputLayerCount,
		int renderWidth,
		int renderHeight,
		int outputWidth,
		int outputHeight,
		float jitterX,
		float jitterY,
		float motionScaleX,
		float motionScaleY,
		float frameTimeMilliseconds,
		boolean resetHistory
	);
}
