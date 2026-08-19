package me.snowmii.dlss.fg

import me.snowmii.streamline.CameraConstants
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FgCameraConstantsTest {

	@Test
	fun `rowMajorOf carries JOML payload without transposing it`() {
		val matrix = Matrix4f().set(
			1f, 2f, 3f, 4f,
			5f, 6f, 7f, 8f,
			9f, 10f, 11f, 12f,
			13f, 14f, 15f, 16f,
		)
		val expected = floatArrayOf(
			1f, 2f, 3f, 4f,
			5f, 6f, 7f, 8f,
			9f, 10f, 11f, 12f,
			13f, 14f, 15f, 16f,
		)

		assertTrue(
			CameraConstants.rowMajorOf(matrix).contentEquals(expected),
			"rowMajorOf must pass JOML's payload through unchanged",
		)
	}

	@Test
	fun `perspective projection keeps Streamline w term at index eleven`() {
		val projection = Matrix4f().perspective(1.2217305f, 16f / 9f, 0.05f, 1000f)
		val payload = CameraConstants.rowMajorOf(projection)

		assertEquals(-1f, payload[11], "perspective w term must sit at flat index 11")
		assertTrue(payload[14] != -1f, "index 14 must carry depth translation, not w term")
	}
}
