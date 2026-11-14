package me.snowmii.dlss.render
import me.snowmii.streamline.Dimensions

import org.joml.Matrix4f

data class DlssCameraSample(
	val projection: Matrix4f,
	val viewRotation: Matrix4f,
	val cameraX: Double,
	val cameraY: Double,
	val cameraZ: Double,
)

data class DlssFrameMotion(
	val reprojection: Matrix4f,
	val motionScaleX: Float,
	val motionScaleY: Float,
	val frameTimeMillis: Float,
	val reset: Boolean,
	val clipToPrevClip: Matrix4f = Matrix4f(),
	val cameraDeltaY: Float = 0f,
)

class DlssCameraMotion(renderDimensions: Dimensions) {
	init {
		require(renderDimensions.width > 0 && renderDimensions.height > 0) {
			"Render dimensions must be positive"
		}
	}

	private val motionScaleX: Float = renderDimensions.width / 2f
	private val motionScaleY: Float = renderDimensions.height / 2f

	private val transforms = arrayOf(Matrix4f(), Matrix4f())
	private val inverse = Matrix4f()
	private var currentIndex = 0
	private var hasPrevious = false
	private var previousCameraX = 0.0
	private var previousCameraY = 0.0
	private var previousCameraZ = 0.0
	private var previousNanos = 0L

	fun advance(camera: DlssCameraSample, jitter: DlssJitterOffset, nowNanos: Long): DlssFrameMotion {
		val current = transforms[currentIndex].set(camera.projection).mul(camera.viewRotation)
		val previous = transforms[1 - currentIndex]
		val continuous = hasPrevious && !jumped(camera, nowNanos)
		val cameraDeltaY = if (!hasPrevious) {
			0f
		} else {
			(camera.cameraY - previousCameraY).toFloat()
		}
		val clipToPrevClip = if (!continuous) {
			Matrix4f()
		} else {
			Matrix4f(previous)
				.translate(
					(camera.cameraX - previousCameraX).toFloat(),
					cameraDeltaY,
					(camera.cameraZ - previousCameraZ).toFloat(),
				)
				.mul(current.invert(inverse))
		}
		val reprojection = if (!continuous) {
			Matrix4f()
		} else {
			Matrix4f()
				.translation(jitter.clipOffsetX, jitter.clipOffsetY, 0f)
				.mul(clipToPrevClip)
				.translate(-jitter.clipOffsetX, -jitter.clipOffsetY, 0f)
		}
		val frameTimeMillis = if (!continuous) {
			0f
		} else {
			(nowNanos - previousNanos) / NANOS_PER_MILLI
		}
		val reset = !continuous

		currentIndex = 1 - currentIndex
		hasPrevious = true
		previousCameraX = camera.cameraX
		previousCameraY = camera.cameraY
		previousCameraZ = camera.cameraZ
		previousNanos = nowNanos

		return DlssFrameMotion(
			reprojection = reprojection,
			clipToPrevClip = clipToPrevClip,
			motionScaleX = motionScaleX,
			motionScaleY = motionScaleY,
			frameTimeMillis = frameTimeMillis,
			reset = reset,
			cameraDeltaY = cameraDeltaY,
		)
	}

	private fun jumped(camera: DlssCameraSample, nowNanos: Long): Boolean {
		val deltaX = camera.cameraX - previousCameraX
		val deltaY = camera.cameraY - previousCameraY
		val deltaZ = camera.cameraZ - previousCameraZ
		val elapsedSeconds = ((nowNanos - previousNanos) / NANOS_PER_SECOND).coerceAtLeast(MIN_INTERVAL_SECONDS)
		val allowed = MAX_CONTINUOUS_SPEED * elapsedSeconds
		return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ > allowed * allowed
	}

	fun reset() {
		hasPrevious = false
	}

	private companion object {
		const val NANOS_PER_MILLI = 1_000_000f
		const val NANOS_PER_SECOND = 1_000_000_000.0

		const val MAX_CONTINUOUS_SPEED = 128.0

		const val MIN_INTERVAL_SECONDS = 0.05
	}
}
