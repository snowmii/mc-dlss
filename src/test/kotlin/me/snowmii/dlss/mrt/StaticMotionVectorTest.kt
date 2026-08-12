package me.snowmii.dlss.mrt

import kotlin.math.abs
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.render.DlssCameraMotion
import me.snowmii.dlss.render.DlssCameraSample
import me.snowmii.dlss.render.DlssFrameMotion
import me.snowmii.dlss.render.DlssJitterOffset
import org.joml.Matrix4f
import org.joml.Vector4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Proves the M-5 still-camera semantics (AC-3): a continuous still camera produces zero NDC
 * motion at every depth through the shader's own per-pixel formula, whatever the jitter moved.
 *
 * This is the camera-motion math the whole velocity surface shares: the stress pass derives its
 * vectors from the same published reprojection, the camera-only writer and the post-scene fill
 * reconstruct the same camera motion, and a static surface's correct velocity is exactly the
 * camera's. The terrain camera-motion writers are retired, so nothing here pins their shader or
 * uniform surface; the retained shader contract is proven in [VelocityWriterContractTest] and
 * the stress pass's own seam in [StressPassVelocityTest].
 */
class StaticMotionVectorTest {

	/**
	 * A continuous still camera produces zero NDC motion at every depth through the shader's
	 * own formula: the reprojection collapses to the identity, so
	 * `ndc(Reprojection * clip) - ndc(clip)` is zero and the jitter never leaks into the
	 * vector. This is the "static motion vectors" the milestone names: static geometry's
	 * correct velocity is exactly the camera's, and a still camera must read zero everywhere.
	 */
	@Test
	fun `a continuous still camera produces zero NDC motion at every depth`() {
		val motion = DlssCameraMotion(RENDER_DIMENSIONS)
		val camera = sample()
		motion.advance(camera, jitter(0, -0.44f, 0.31f), 0L)
		val frame = motion.advance(camera, jitter(1, 0.37f, -0.21f), 16_000_000L)

		assertFalse(frame.reset, "a continuous still camera is not a reset frame")
		for (probe in probes) {
			val motionVector = motionOf(frame, probe)
			assertEquals(0f, motionVector.x, TOLERANCE, "x motion at $probe")
			assertEquals(0f, motionVector.y, TOLERANCE, "y motion at $probe")
		}
	}

	private fun sample() = DlssCameraSample(projection, Matrix4f(), 0.0, 64.0, 0.0)

	private fun jitter(index: Int, pixelX: Float, pixelY: Float) =
		DlssJitterOffset(index, pixelX, pixelY, RENDER_DIMENSIONS)

	/** Sample points spread across the frustum, from near the eye to the far plane. */
	private val probes = listOf(
		Vector4f(0f, 0f, 0.95f, 1f),
		Vector4f(0.4f, 0.3f, 0.6f, 1f),
		Vector4f(-0.5f, 0.2f, 0.25f, 1f),
		Vector4f(0.1f, -0.4f, 0.05f, 1f),
	)

	/**
	 * The shader's own per-pixel formula: `ndc` is this fragment's normalized device
	 * coordinates recovered from gl_FragCoord, `clip = vec4(ndc, gl_FragCoord.z, 1.0)`,
	 * `previous = Reprojection * clip`, `motion = previous.xy / previous.w - ndc`.
	 */
	private fun motionOf(frame: DlssFrameMotion, clip: Vector4f): Vector4f {
		val reprojected = frame.reprojection.transform(Vector4f(clip))
		return Vector4f(
			reprojected.x / reprojected.w - clip.x / clip.w,
			reprojected.y / reprojected.w - clip.y / clip.w,
			0f,
			1f,
		)
	}

	private val projection: Matrix4f = Matrix4f().setPerspective(
		Math.toRadians(70.0).toFloat(),
		RENDER_DIMENSIONS.width.toFloat() / RENDER_DIMENSIONS.height,
		1000f,
		0.05f,
		true,
	)

	private companion object {
		const val TOLERANCE = 1e-3f
	}
}
