package me.snowmii.dlss.render
import me.snowmii.dlss.bridge.DlssDimensions

import kotlin.math.ceil

/**
 * One world frame's sub-pixel camera jitter.
 *
 * [pixelX] and [pixelY] are offsets from the pixel centre in *render-target* pixels, which is
 * the unit NGX's `InJitterOffsetX/Y` expects. [clipOffsetX] and [clipOffsetY] are the same
 * offset expressed as the normalized-device translation the world projection must carry. Minecraft's
 * Vulkan viewport and Streamline jitter convention use the same signed offset on both axes, so these
 * are two views of one value rather than independent calculations that can drift apart.
 */
data class DlssJitterOffset(
	val index: Int,
	val pixelX: Float,
	val pixelY: Float,
	val renderDimensions: DlssDimensions,
) {
	/** Normalized-device X translation for [pixelX] at [renderDimensions]. */
	val clipOffsetX: Float
		get() = 2f * pixelX / renderDimensions.width

	/** Normalized-device Y translation for [pixelY] at [renderDimensions]. */
	val clipOffsetY: Float
		get() = 2f * pixelY / renderDimensions.height
}

/**
 * Deterministic sub-pixel jitter sequence for one render/output dimension pair.
 *
 * DLSS reconstructs a full-resolution image from a low-resolution one by accumulating frames
 * that each sample a slightly different point inside the same pixel. The sequence supplying
 * those points has to be low-discrepancy - it must fill the pixel evenly rather than clump -
 * and it has to repeat, so history stays bounded. Halton (2, 3) is the standard choice and is
 * what NVIDIA's own integration guide uses.
 *
 * [phaseCount] scales with the square of the upscale ratio: the more output pixels each render
 * pixel has to reconstruct, the more distinct samples the sequence needs before repeating.
 *
 * The sequence is a pure function of its index, so a session that renders the same frame index
 * twice jitters it identically. That is what makes the coherence between the projection matrix
 * and the evaluation parameter checkable off the render thread.
 */
class DlssJitter(
	private val renderDimensions: DlssDimensions,
	outputDimensions: DlssDimensions,
) {
	init {
		require(renderDimensions.width > 0 && renderDimensions.height > 0) {
			"Render dimensions must be positive"
		}
		require(outputDimensions.width > 0 && outputDimensions.height > 0) {
			"Output dimensions must be positive"
		}
	}

	/** Number of distinct offsets before the sequence repeats. */
	val phaseCount: Int = phaseCountFor(renderDimensions, outputDimensions)

	private var index = 0

	/** Advances to the next phase and returns its offset. */
	fun advance(): DlssJitterOffset {
		// Halton index 0 is exactly 0 in every base, which would put one sample on the pixel
		// corner instead of inside the pixel; the sequence therefore starts at 1.
		val haltonIndex = index + 1
		val offset = DlssJitterOffset(
			index = index,
			pixelX = radicalInverse(haltonIndex, 2) - 0.5f,
			pixelY = radicalInverse(haltonIndex, 3) - 0.5f,
			renderDimensions = renderDimensions,
		)
		index = (index + 1) % phaseCount
		return offset
	}

	/**
	 * Returns the sequence to its start.
	 *
	 * Any frame that does not go through DLSS breaks the accumulated history, so the next DLSS
	 * frame must start the sequence again rather than continue a run the history no longer
	 * matches.
	 */
	fun reset() {
		index = 0
	}

	companion object {
		/** Phases at 1:1, from NVIDIA's DLSS integration guidance. */
		const val BASE_PHASE_COUNT = 8

		/** `BASE_PHASE_COUNT * ratio^2`, using the wider of the two axis ratios. */
		private fun phaseCountFor(renderDimensions: DlssDimensions, outputDimensions: DlssDimensions): Int {
			val ratio = maxOf(
				outputDimensions.width.toDouble() / renderDimensions.width,
				outputDimensions.height.toDouble() / renderDimensions.height,
			).coerceAtLeast(1.0)
			return ceil(BASE_PHASE_COUNT * ratio * ratio).toInt()
		}

		/** Van der Corput radical inverse of [index] in [base], in `[0, 1)`. */
		private fun radicalInverse(index: Int, base: Int): Float {
			var result = 0.0
			var fraction = 1.0 / base
			var remaining = index
			while (remaining > 0) {
				result += (remaining % base) * fraction
				remaining /= base
				fraction /= base
			}
			return result.toFloat()
		}
	}
}
