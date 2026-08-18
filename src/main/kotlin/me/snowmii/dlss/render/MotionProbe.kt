package me.snowmii.dlss.render

import me.snowmii.streamline.MotionProbeSample
import org.joml.Matrix4f
import org.joml.Vector2f
import org.joml.Vector4f
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Same-frame comparison of the GPU motion/depth sample at screen centre against the CPU
 * reprojection of that depth. One frame of GPU work is in flight, so the readable slot is two
 * records old; [recordFrame] and the native dispatch share a 3-slot ring so the matrix and the
 * sample still name the same frame.
 */
object MotionProbe {
	internal const val RING = 3

	private val cpu = arrayOfNulls<CpuFrame>(RING)
	private var records = 0

	fun recordFrame(motion: DlssFrameMotion) {
		val slot = records % RING
		cpu[slot] = CpuFrame(
			Matrix4f(motion.reprojection),
			motion.cameraDeltaY,
			motion.reset,
			(motion.motionScaleX * 2f).toInt(),
			(motion.motionScaleY * 2f).toInt(),
		)
		records++
	}

	fun line(sample: MotionProbeSample?): String {
		if (sample == null || records < RING) {
			return "DLSS motion: warming"
		}
		val frame = cpu[sample.slot] ?: return "DLSS motion: warming"
		if (frame.reset || abs(sample.motionX) > SENTINEL) {
			return "DLSS motion: reset"
		}
		val expected = expectedMotion(frame.reprojection, frame.width, frame.height, sample.depth)
		val gpu = hypot(sample.motionX, sample.motionY)
		val exp = hypot(expected.x, expected.y)
		val err = hypot(sample.motionX - expected.x, sample.motionY - expected.y)
		val flipped = hypot(sample.motionX - expected.x, sample.motionY + expected.y)
		val jumping = abs(frame.cameraDeltaY) > JUMP || exp > MATCH
		val errPx = pixels(sample.motionX - expected.x, sample.motionY - expected.y, frame.width, frame.height)
		val gpuPx = pixels(sample.motionX, sample.motionY, frame.width, frame.height)
		return when {
			jumping && sample.depth <= CLEARED -> "DLSS motion: empty depth"
			gpu < ZERO && exp > ZERO -> "DLSS motion: no vector"
			jumping && gpu > ZERO && exp > ZERO && sample.motionY * expected.y < 0f && flipped < err * 0.5f ->
				"DLSS motion: Y flipped"
			err > MATCH -> "DLSS motion: WRONG ${px(errPx)}"
			!jumping && gpuPx > RESIDUAL_PX -> "DLSS motion: residual ${px(gpuPx)}"
			jumping -> "DLSS motion: OK ${px(errPx)}"
			else -> "DLSS motion: still"
		}
	}

	private fun pixels(ndcX: Float, ndcY: Float, width: Int, height: Int): Float =
		hypot(ndcX * width * 0.5f, ndcY * height * 0.5f)

	private fun px(value: Float): String {
		val tenths = (value * 10f + 0.5f).toInt()
		return "${tenths / 10}.${tenths % 10}px"
	}

	/**
	 * The motion shader's centre-pixel job: NDC from the pixel centre, reproject, subtract.
	 */
	fun expectedMotion(reprojection: Matrix4f, width: Int, height: Int, depth: Float): Vector2f {
		val pixelX = width / 2
		val pixelY = height / 2
		val ndcX = ((pixelX + 0.5f) / width) * 2f - 1f
		val ndcY = ((pixelY + 0.5f) / height) * 2f - 1f
		val previous = reprojection.transform(Vector4f(ndcX, ndcY, depth, 1f))
		if (previous.w == 0f) {
			return Vector2f(0f, 0f)
		}
		return Vector2f(previous.x / previous.w - ndcX, previous.y / previous.w - ndcY)
	}

	internal fun clear() {
		cpu.fill(null)
		records = 0
	}

	private class CpuFrame(
		val reprojection: Matrix4f,
		val cameraDeltaY: Float,
		val reset: Boolean,
		val width: Int,
		val height: Int,
	)

	private const val MATCH = 0.02f
	/** Below this the GPU wrote nothing; 0.005 ate a jump at ~15 blocks. */
	private const val ZERO = 1e-4f
	private const val JUMP = 0.03f
	/** Reversed-Z clear/sky. 0.02 is ~2.5 blocks, which made the whole world read empty. */
	private const val CLEARED = 1e-6f
	private const val SENTINEL = 100f
	/** Still-camera GPU motion above this is jitter leaking into the vector, not a still lock. */
	private const val RESIDUAL_PX = 0.25f
}
