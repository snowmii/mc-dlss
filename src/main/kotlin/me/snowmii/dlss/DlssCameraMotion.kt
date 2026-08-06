package me.snowmii.dlss

import org.joml.Matrix4f

/**
 * One frame's camera, as the world projection seam sees it.
 *
 * [projection] is the *unjittered* world projection Minecraft is about to upload - already
 * carrying view bob and the portal/nausea skew, because those move the camera as surely as
 * walking does - and [viewRotation] is `CameraRenderState.viewRotationMatrix`, which maps
 * camera-relative world axes into view space. Minecraft renders the world camera-relative, so
 * the camera's own world position is carried separately in [cameraX], [cameraY], and [cameraZ]:
 * a static block has different coordinates in consecutive frames precisely by how far the camera
 * moved.
 *
 * Neither matrix is copied. Minecraft reuses both across frames, so a sample is only valid for
 * the call it is handed to.
 */
data class DlssCameraSample(
	val projection: Matrix4f,
	val viewRotation: Matrix4f,
	val cameraX: Double,
	val cameraY: Double,
	val cameraZ: Double,
)

/**
 * Everything NGX needs to know about how the camera moved into one frame.
 *
 * [reprojection] maps a pixel's *jittered* clip position - what the rendered frame and its depth
 * buffer actually hold - to where that same surface point sat in the previous frame, expressed
 * so that
 *
 * ```
 * motionNdc = ndc(reprojection * clip) - ndc(clip)
 * ```
 *
 * is the jitter-free camera motion vector in normalized device units. [motionScaleX] and
 * [motionScaleY] convert that vector to render pixels, which is the unit NGX's `InMVScale`
 * expects to arrive at.
 *
 * [frameTimeMillis] is the wall time since the previous DLSS frame. [reset] marks a frame whose
 * accumulated history is worthless - no predecessor, or a predecessor that never went through
 * DLSS - and its [reprojection] is the identity, because there is no previous frame to point at.
 */
data class DlssFrameMotion(
	val reprojection: Matrix4f,
	val motionScaleX: Float,
	val motionScaleY: Float,
	val frameTimeMillis: Float,
	val reset: Boolean,
)

/**
 * Tracks the camera across DLSS frames and derives each frame's camera-only motion.
 *
 * DLSS needs to know, for every pixel, where that surface point was in the previous frame. With
 * camera-only motion - no object motion, which this effort excludes - that is a pure function of
 * two frames' view-projection transforms and the distance the camera travelled between them, so
 * it collapses into a single matrix rather than a per-object pass.
 *
 * The one subtlety is jitter. The rendered frame is jittered, so a pixel's clip position carries
 * the current frame's offset, but the motion vector must not: DLSS is told the jitter separately
 * through `InJitterOffset` and would double-count it. Jitter is a clip-space translation `T(j)`,
 * so composing
 *
 * ```
 * reprojection = T(j) * previousViewProjection * T(cameraDelta) * inverse(viewProjection) * T(-j)
 * ```
 *
 * strips the current offset off the incoming position, walks the point back through the previous
 * camera, and re-adds the offset so it cancels against the untouched `ndc(clip)` term of the
 * subtraction. A still camera therefore reduces to the identity no matter how the jitter moved,
 * which is the cheapest check that the two halves stay coherent.
 *
 * Nothing here touches `clip.z` or `clip.w` beyond the transforms themselves, so reversed-Z depth
 * keeps meaning what it meant.
 *
 * Deliberately left open, and the same open question the jitter carries: whether Minecraft's
 * Vulkan Y orientation needs the Y component negated before NGX reads it. A live DLSS frame is
 * the only thing that decides it, and it is one sign in one place either way.
 */
class DlssCameraMotion(renderDimensions: DlssDimensions) {
	init {
		require(renderDimensions.width > 0 && renderDimensions.height > 0) {
			"Render dimensions must be positive"
		}
	}

	private val motionScaleX: Float = renderDimensions.width / 2f
	private val motionScaleY: Float = renderDimensions.height / 2f

	// Two owned transforms, swapped each frame, so the only per-frame allocation is the
	// reprojection that leaves this class. The render loop calls advance once a frame.
	private val transforms = arrayOf(Matrix4f(), Matrix4f())
	private val inverse = Matrix4f()
	private var currentIndex = 0
	private var hasPrevious = false
	private var previousCameraX = 0.0
	private var previousCameraY = 0.0
	private var previousCameraZ = 0.0
	private var previousNanos = 0L

	/** Derives this frame's motion and becomes the predecessor of the next one. */
	fun advance(camera: DlssCameraSample, jitter: DlssJitterOffset, nowNanos: Long): DlssFrameMotion {
		val current = transforms[currentIndex].set(camera.projection).mul(camera.viewRotation)
		val previous = transforms[1 - currentIndex]
		val reprojection = if (!hasPrevious) {
			Matrix4f()
		} else {
			Matrix4f()
				.translation(jitter.clipOffsetX, jitter.clipOffsetY, 0f)
				.mul(previous)
				// Camera-relative: the point sat one camera-delta further along last frame.
				.translate(
					(camera.cameraX - previousCameraX).toFloat(),
					(camera.cameraY - previousCameraY).toFloat(),
					(camera.cameraZ - previousCameraZ).toFloat(),
				)
				.mul(current.invert(inverse))
				.translate(-jitter.clipOffsetX, -jitter.clipOffsetY, 0f)
		}
		val frameTimeMillis = if (!hasPrevious) {
			// No predecessor means no interval to report, and reset tells DLSS to ignore history
			// anyway; a fabricated duration would only be a lie it could act on.
			0f
		} else {
			(nowNanos - previousNanos) / NANOS_PER_MILLI
		}
		val reset = !hasPrevious

		currentIndex = 1 - currentIndex
		hasPrevious = true
		previousCameraX = camera.cameraX
		previousCameraY = camera.cameraY
		previousCameraZ = camera.cameraZ
		previousNanos = nowNanos

		return DlssFrameMotion(
			reprojection = reprojection,
			motionScaleX = motionScaleX,
			motionScaleY = motionScaleY,
			frameTimeMillis = frameTimeMillis,
			reset = reset,
		)
	}

	/**
	 * Forgets the predecessor, so the next frame reports [DlssFrameMotion.reset].
	 *
	 * Any frame DLSS did not accumulate breaks the chain: a vanilla frame, a frame routed
	 * without a camera, and a frame abandoned by an exception all leave the history pointing at
	 * a camera the next frame must not reproject against.
	 */
	fun reset() {
		hasPrevious = false
	}

	private companion object {
		const val NANOS_PER_MILLI = 1_000_000f
	}
}
