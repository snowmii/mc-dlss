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

	int configure(
		int outputWidth,
		int outputHeight,
		int renderWidth,
		int renderHeight,
		int qualityMode
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
