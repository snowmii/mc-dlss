package me.snowmii.dlss.api

import me.snowmii.dlss.RRModelPreset
import me.snowmii.dlss.api.ImageBinding
import java.util.function.Supplier

/**
 * DLSS Ray Reconstruction arming from a guide producer (Lumina).
 *
 * When [enabled] and [guides] returns a frame, mc-dlss tags those images and evaluates
 * `kFeatureDLSS_RR` instead of Super Resolution. See Lumina `docs/rr-guides.md`.
 */
object RayReconstruction {
	/** Native-resolution raw accumulation requested by a guide producer. Does not change user settings. */
	@JvmField
	@Volatile
	var referenceMode: Boolean = false

	@JvmField
	@Volatile
	var enabled: Boolean = false

	/** RR model preset applied on the next armed prepare. */
	@JvmField
	@Volatile
	var modelPreset: RRModelPreset = RRModelPreset.DEFAULT

	@JvmField
	@Volatile
	var guides: Supplier<RayReconstructionGuides?>? = null

	@JvmField
	@Volatile
	var exposure: Float = 1.0f

	@Volatile
	private var resetRequested: Boolean = false

	/** Request DLSS history reset on the next evaluation frame. */
	@JvmStatic
	fun requestHistoryReset() {
		resetRequested = true
	}

	@JvmStatic
	fun consumeReset(): Boolean {
		if (!resetRequested) return false
		resetRequested = false
		return true
	}
}

/**
 * One frame of RR guide bindings. [noisyColor] is ScalingInputColor (HDR).
 * [depth] optionally replaces device depth for virtual mirror surfaces. mc-dlss reconstructs
 * camera motion from the same depth; object velocities and frame-generation depth stay unchanged.
 */
data class RayReconstructionGuides(
	val noisyColor: ImageBinding,
	val diffuseAlbedo: ImageBinding,
	val specularAlbedo: ImageBinding,
	val normalRoughness: ImageBinding,
	val specularHitDistance: ImageBinding = ImageBinding(0L, 0L, 0),
	val resetHistory: Boolean = false,
	val depth: ImageBinding? = null,
	/** Reflection motion in the same NDC units as the main motion field. */
	val specularMotion: ImageBinding? = null,
	/** Fresnel-weighted HDR reflection layer, added after RR and before tone mapping. */
	val reflection: ImageBinding? = null,
	/**
	 * Current-frame per-pixel history rejection hint (Streamline BiasCurrentColorHint, not ReactiveMask).
	 * Input-color-sized binary R channel: 0 retains history, 1 biases current color. Supported formats:
	 * R8_UNORM, R16_SFLOAT, R8G8B8A8_UNORM. Producer owns contents, size and lifetime through present.
	 * Null clears the hint; it never requests a whole-frame reset. SDK support is model-dependent.
	 */
	val biasCurrentColor: ImageBinding? = null,
)
