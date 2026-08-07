package me.snowmii.dlss.render
import me.snowmii.dlss.bridge.DlssDimensions

import org.joml.Matrix4f
import org.joml.Vector4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Proves the jittered projection shifts the image by exactly the frame's offset, at every
 * depth, without disturbing reversed-Z depth.
 *
 * The matrix is built the way Minecraft 26.2 builds the world projection: `Projection.getMatrix`
 * passes `near = zFar, far = zNear` into JOML with zero-to-one depth, which is what makes the
 * effort's depth reversed in the first place.
 */
class DlssProjectionJitterTest {
	private val render = DlssDimensions(1280, 720)
	private val output = DlssDimensions(2560, 1440)
	private val tolerance = 1e-6f

	private val reversedZ: Matrix4f = Matrix4f().setPerspective(
		Math.toRadians(70.0).toFloat(),
		render.width.toFloat() / render.height,
		1000f,
		0.05f,
		true,
	)

	@Test
	fun `the image shifts by exactly the offset in normalized device coordinates`() {
		val offset = DlssJitter(render, output).advance()
		val jittered = DlssProjectionJitter.apply(reversedZ, offset, Matrix4f())

		val plain = ndc(reversedZ, 3f, -2f, -12f)
		val shifted = ndc(jittered, 3f, -2f, -12f)

		assertEquals(offset.clipOffsetX, shifted.x - plain.x, tolerance)
		assertEquals(offset.clipOffsetY, shifted.y - plain.y, tolerance)
	}

	@Test
	fun `the shift is the same at every depth`() {
		val offset = DlssJitter(render, output).advance()
		val jittered = DlssProjectionJitter.apply(reversedZ, offset, Matrix4f())

		val near = ndc(jittered, 3f, -2f, -1f).sub(ndc(reversedZ, 3f, -2f, -1f))
		val far = ndc(jittered, 3f, -2f, -400f).sub(ndc(reversedZ, 3f, -2f, -400f))

		assertEquals(near.x, far.x, tolerance)
		assertEquals(near.y, far.y, tolerance)
	}

	@Test
	fun `depth is untouched, so reversed-Z still means what it meant`() {
		val offset = DlssJitter(render, output).advance()
		val jittered = DlssProjectionJitter.apply(reversedZ, offset, Matrix4f())

		for (viewZ in listOf(-1f, -12f, -400f)) {
			assertEquals(ndc(reversedZ, 3f, -2f, viewZ).z, ndc(jittered, 3f, -2f, viewZ).z, tolerance)
		}
		// Reversed-Z: nearer geometry must produce the larger depth value.
		val nearDepth = ndc(jittered, 0f, 0f, -1f).z
		val farDepth = ndc(jittered, 0f, 0f, -400f).z
		assertTrue(nearDepth > farDepth, "expected reversed-Z depth, got $nearDepth then $farDepth")
	}

	@Test
	fun `a half-pixel offset moves the image by exactly half a render pixel`() {
		val offset = DlssJitterOffset(index = 0, pixelX = 0.5f, pixelY = -0.5f, renderDimensions = render)
		val jittered = DlssProjectionJitter.apply(reversedZ, offset, Matrix4f())

		val plain = ndc(reversedZ, 3f, -2f, -12f)
		val shifted = ndc(jittered, 3f, -2f, -12f)

		// One render pixel spans 2 / dimension in normalized device coordinates.
		assertEquals(1f / render.width, shifted.x - plain.x, tolerance)
		assertEquals(-1f / render.height, shifted.y - plain.y, tolerance)
	}

	@Test
	fun `the destination is written in place and the source projection is left alone`() {
		val offset = DlssJitter(render, output).advance()
		val source = Matrix4f(reversedZ)
		val destination = Matrix4f().scaling(7f)

		val result = DlssProjectionJitter.apply(source, offset, destination)

		assertSame(destination, result)
		assertEquals(reversedZ, source)
	}

	private fun ndc(projection: Matrix4f, x: Float, y: Float, z: Float): Vector4f {
		val clip = projection.transform(Vector4f(x, y, z, 1f))
		return Vector4f(clip.x / clip.w, clip.y / clip.w, clip.z / clip.w, 1f)
	}
}
