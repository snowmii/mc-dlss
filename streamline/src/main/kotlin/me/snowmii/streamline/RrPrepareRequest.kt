package me.snowmii.streamline

import me.snowmii.vklib.api.ImageBinding

/**
 * Arms or clears DLSS Ray Reconstruction for the next SR tag + evaluate.
 *
 * When [enabled] is false, guide images and matrices may be zeroed.
 * [rrPreset] is an `sl::DLSSDPreset` value: 0 = NVIDIA App / OTA, else D=4 / E=5 / F=6.
 */
data class RrPrepareRequest(
	val enabled: Boolean,
	val rrPreset: Int = 0,
	val diffuseAlbedo: ImageBinding = ImageBinding(0L, 0L, 0),
	val specularAlbedo: ImageBinding = ImageBinding(0L, 0L, 0),
	val normalRoughness: ImageBinding = ImageBinding(0L, 0L, 0),
	val specularHitDistance: ImageBinding = ImageBinding(0L, 0L, 0),
	val worldToCameraView: FloatArray = FloatArray(16),
	val cameraViewToWorld: FloatArray = FloatArray(16),
) {
	init {
		require(worldToCameraView.size == 16) { "worldToCameraView must be 16 floats" }
		require(cameraViewToWorld.size == 16) { "cameraViewToWorld must be 16 floats" }
	}
}
