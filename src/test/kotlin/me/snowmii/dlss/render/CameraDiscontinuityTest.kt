package me.snowmii.dlss.render
import me.snowmii.dlss.bridge.DlssDimensions

import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A camera that jumped is a discontinuity, and DLSS history must not survive it.
 *
 * Every other break in the chain is already visible from outside the camera: a vanilla frame, a
 * frame routed without a camera, an abandoned phase. A teleport, a respawn, or a dimension change
 * is not - every frame stays eligible and carries a camera, and only the camera itself says the
 * scene it describes is a different one. Reprojecting across that jump points NGX at geometry that
 * no longer exists anywhere in the frame, which is exactly what `reset` exists to prevent.
 *
 * The threshold is a speed, not a distance, because the same displacement means different things
 * in a 4 ms frame and a 400 ms one. Below it every continuous movement Minecraft can produce must
 * survive untouched, or the accumulation this effort exists for would restart while simply flying.
 */
class CameraDiscontinuityTest {
	private val render = DlssDimensions(1280, 720)
	private val tolerance = 1e-4f

	private val projection: Matrix4f = Matrix4f().setPerspective(
		Math.toRadians(70.0).toFloat(),
		render.width.toFloat() / render.height,
		1000f,
		0.05f,
		true,
	)

	private val previousOffset = DlssJitterOffset(0, pixelX = -0.44f, pixelY = 0.31f, renderDimensions = render)
	private val currentOffset = DlssJitterOffset(1, pixelX = 0.37f, pixelY = -0.21f, renderDimensions = render)

	@Test
	fun `a camera that teleported resets history instead of reprojecting across the jump`() {
		val motion = DlssCameraMotion(render)

		motion.advance(sample(x = 120.0, z = -40.0), previousOffset, nanos(0))
		val jumped = motion.advance(sample(x = 12_000.0, z = -40.0), currentOffset, nanos(16))

		assertTrue(jumped.reset, "a teleported camera must not be reprojected against")
		assertEquals(Matrix4f(), jumped.reprojection)
		assertEquals(0f, jumped.frameTimeMillis)
	}

	@Test
	fun `a jump on any axis is a discontinuity`() {
		for (jumped in listOf(sample(x = 900.0), sample(y = 900.0), sample(z = 900.0))) {
			val motion = DlssCameraMotion(render)

			motion.advance(sample(), previousOffset, nanos(0))

			assertTrue(
				motion.advance(jumped, currentOffset, nanos(16)).reset,
				"expected reset after jumping to (${jumped.cameraX}, ${jumped.cameraY}, ${jumped.cameraZ})",
			)
		}
	}

	@Test
	fun `the frame after a discontinuity accumulates from the camera that replaced it`() {
		val motion = DlssCameraMotion(render)

		motion.advance(sample(), previousOffset, nanos(0))
		motion.advance(sample(x = 12_000.0), currentOffset, nanos(16))
		val settled = motion.advance(sample(x = 12_000.5), currentOffset, nanos(32))

		assertFalse(settled.reset, "the jump is one frame, not a latched reset")
		assertEquals(16f, settled.frameTimeMillis, tolerance)
		assertNotEquals(Matrix4f(), settled.reprojection, "the settled frame must reproject again")
	}

	@Test
	fun `the fastest continuous movement Minecraft can produce keeps its history`() {
		val motion = DlssCameraMotion(render)

		// Rocket-boosted elytra, in the order of 30 blocks per second, through a 50 ms frame.
		motion.advance(sample(), previousOffset, nanos(0))
		val flying = motion.advance(sample(x = 1.5, y = 64.4), currentOffset, nanos(50))

		assertFalse(flying.reset, "flight must accumulate rather than restart every frame")
	}

	@Test
	fun `a long frame is allowed the distance it covers`() {
		val motion = DlssCameraMotion(render)

		// The same speed as the frame above, through a stall an order of magnitude longer.
		motion.advance(sample(), previousOffset, nanos(0))
		val stalled = motion.advance(sample(x = 15.0, y = 68.0), currentOffset, nanos(500))

		assertFalse(stalled.reset, "a slow frame covers more ground at the same speed")
	}

	@Test
	fun `a very short frame is not held to a proportionally tiny step`() {
		val motion = DlssCameraMotion(render)

		// A 1 ms frame at the same speed moves a fraction of a block; the threshold has a floor so
		// a fast frame cannot make any real movement look like a jump.
		motion.advance(sample(), previousOffset, nanos(0))
		val quick = motion.advance(sample(x = 0.03), currentOffset, nanos(1))

		assertFalse(quick.reset)
	}

	@Test
	fun `a discontinuity on the runtime's first eligible frame is still just a reset`() {
		val motion = DlssCameraMotion(render)

		val first = motion.advance(sample(x = 12_000.0), currentOffset, nanos(0))

		assertTrue(first.reset)
		assertEquals(Matrix4f(), first.reprojection)
	}

	private fun sample(
		x: Double = 0.0,
		y: Double = 64.0,
		z: Double = 0.0,
		view: Matrix4f = Matrix4f(),
	) = DlssCameraSample(projection, view, x, y, z)

	private fun nanos(millis: Long) = millis * 1_000_000L
}
