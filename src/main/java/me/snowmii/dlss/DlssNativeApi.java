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
		long depthView,
		long motionView,
		long outputView,
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
