package me.snowmii.dlss.render

import me.snowmii.streamline.MotionProbeSample
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MotionProbeTest {
	@BeforeEach
	fun resetRing() {
		MotionProbe.clear()
	}

	@Test
	fun `identity reprojection at any depth is zero motion`() {
		val expected = MotionProbe.expectedMotion(Matrix4f(), 1280, 720, 0.4f)
		assertEquals(0f, expected.x, 1e-5f)
		assertEquals(0f, expected.y, 1e-5f)
	}

	@Test
	fun `line stays warming until the ring has a readable GPU slot`() {
		repeat(2) { MotionProbe.recordFrame(still()) }
		assertEquals("DLSS motion: warming", MotionProbe.line(null))
		assertEquals("DLSS motion: warming", MotionProbe.line(MotionProbeSample(0.1f, 0.2f, 0.5f, 0)))
	}

	@Test
	fun `a still camera with matching zero vectors reads still`() {
		repeat(3) { MotionProbe.recordFrame(still()) }
		assertEquals("DLSS motion: still", MotionProbe.line(MotionProbeSample(0f, 0f, 0.5f, 0)))
	}

	@Test
	fun `matching jump vectors read OK`() {
		val moved = still(deltaY = 0.4f).copy(reprojection = Matrix4f().translation(0.1f, 0f, 0f))
		MotionProbe.recordFrame(still())
		MotionProbe.recordFrame(moved)
		MotionProbe.recordFrame(still())
		assertEquals("DLSS motion: OK 0.0px", MotionProbe.line(MotionProbeSample(0.1f, 0f, 0.5f, 1)))
	}

	@Test
	fun `a jump with no GPU vector says so`() {
		val moved = still(deltaY = 0.4f).copy(reprojection = Matrix4f().translation(0.1f, 0f, 0f))
		MotionProbe.recordFrame(still())
		MotionProbe.recordFrame(moved)
		MotionProbe.recordFrame(still())
		assertEquals("DLSS motion: no vector", MotionProbe.line(MotionProbeSample(0f, 0f, 0.7f, 1)))
	}

	@Test
	fun `a jump with small matching far motion is OK`() {
		val moved = still(deltaY = 0.4f).copy(reprojection = Matrix4f().translation(0.003f, 0f, 0f))
		MotionProbe.recordFrame(still())
		MotionProbe.recordFrame(moved)
		MotionProbe.recordFrame(still())
		assertEquals("DLSS motion: OK 0.0px", MotionProbe.line(MotionProbeSample(0.003f, 0f, 0.01f, 1)))
	}

	@Test
	fun `cleared depth on a jump is empty depth`() {
		MotionProbe.recordFrame(still())
		MotionProbe.recordFrame(still(deltaY = 0.4f))
		MotionProbe.recordFrame(still())
		assertEquals("DLSS motion: empty depth", MotionProbe.line(MotionProbeSample(0f, 0f, 0f, 1)))
	}

	@Test
	fun `mid-range reversed-Z depth on a jump is not empty`() {
		MotionProbe.recordFrame(still())
		MotionProbe.recordFrame(still(deltaY = 0.4f))
		MotionProbe.recordFrame(still())
		assertEquals("DLSS motion: OK 0.0px", MotionProbe.line(MotionProbeSample(0f, 0f, 0.01f, 1)))
	}

	@Test
	fun `gpu disagreeing with cpu is WRONG`() {
		repeat(3) { MotionProbe.recordFrame(still()) }
		assertEquals("DLSS motion: WRONG 12.8px", MotionProbe.line(MotionProbeSample(0.2f, 0f, 0.5f, 0)))
	}

	@Test
	fun `a jump whose GPU Y is negated is Y flipped`() {
		val moved = still(deltaY = 0.4f).copy(reprojection = Matrix4f().translation(0f, 0.1f, 0f))
		MotionProbe.recordFrame(still())
		MotionProbe.recordFrame(moved)
		MotionProbe.recordFrame(still())
		assertEquals("DLSS motion: Y flipped", MotionProbe.line(MotionProbeSample(0f, -0.1f, 0.5f, 1)))
	}

	@Test
	fun `still-camera GPU motion of a third of a pixel is residual`() {
		repeat(3) { MotionProbe.recordFrame(still()) }
		assertEquals("DLSS motion: residual 0.6px", MotionProbe.line(MotionProbeSample(0.01f, 0f, 0.5f, 0)))
	}

	private fun still(deltaY: Float = 0f) = DlssFrameMotion(
		reprojection = Matrix4f(),
		motionScaleX = 64f,
		motionScaleY = 36f,
		frameTimeMillis = 16f,
		reset = false,
		cameraDeltaY = deltaY,
	)
}
