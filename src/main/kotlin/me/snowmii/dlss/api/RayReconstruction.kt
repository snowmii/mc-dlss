package me.snowmii.dlss.api

import me.snowmii.vklib.api.ImageBinding
import java.util.function.Supplier

/**
 * DLSS Ray Reconstruction arming from a guide producer (Lumina).
 *
 * When [enabled] and [guides] returns a frame, mc-dlss tags those images and evaluates
 * `kFeatureDLSS_RR` instead of Super Resolution. See Lumina `docs/rr-guides.md`.
 */
object RayReconstruction {
	@JvmField
	@Volatile
	var enabled: Boolean = false

	@JvmField
	@Volatile
	var guides: Supplier<RayReconstructionGuides?>? = null
}

/**
 * One frame of RR guide bindings. [noisyColor] is ScalingInputColor (HDR).
 * Depth, motion, and camera matrices stay owned by mc-dlss.
 */
data class RayReconstructionGuides(
	val noisyColor: ImageBinding,
	val diffuseAlbedo: ImageBinding,
	val specularAlbedo: ImageBinding,
	val normalRoughness: ImageBinding,
	val specularHitDistance: ImageBinding,
)
