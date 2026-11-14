package me.snowmii.dlss.render
import me.snowmii.streamline.Dimensions

import kotlin.math.ceil

data class DlssJitterOffset(
	val index: Int,
	val pixelX: Float,
	val pixelY: Float,
	val renderDimensions: Dimensions,
) {
	val clipOffsetX: Float
		get() = 2f * pixelX / renderDimensions.width

	val clipOffsetY: Float
		get() = 2f * pixelY / renderDimensions.height
}

class DlssJitter(
	private val renderDimensions: Dimensions,
	outputDimensions: Dimensions,
) {
	init {
		require(renderDimensions.width > 0 && renderDimensions.height > 0) {
			"Render dimensions must be positive"
		}
		require(outputDimensions.width > 0 && outputDimensions.height > 0) {
			"Output dimensions must be positive"
		}
	}

	val phaseCount: Int = phaseCountFor(renderDimensions, outputDimensions)

	private var index = 0
	private var previousRayReconstruction = false

	fun advance(rayReconstruction: Boolean = false): DlssJitterOffset {
		if (rayReconstruction != previousRayReconstruction) reset()
		previousRayReconstruction = rayReconstruction
		val phases = if (rayReconstruction) maxOf(32, phaseCount) else phaseCount
		val haltonIndex = index + 1
		val offset = DlssJitterOffset(
			index = index,
			pixelX = radicalInverse(haltonIndex, 2) - 0.5f,
			pixelY = radicalInverse(haltonIndex, 3) - 0.5f,
			renderDimensions = renderDimensions,
		)
		index = (index + 1) % phases
		return offset
	}

	fun reset() {
		index = 0
	}

	companion object {
		const val BASE_PHASE_COUNT = 8

		private fun phaseCountFor(renderDimensions: Dimensions, outputDimensions: Dimensions): Int {
			val ratio = maxOf(
				outputDimensions.width.toDouble() / renderDimensions.width,
				outputDimensions.height.toDouble() / renderDimensions.height,
			).coerceAtLeast(1.0)
			return ceil(BASE_PHASE_COUNT * ratio * ratio).toInt()
		}

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
