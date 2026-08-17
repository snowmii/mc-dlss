package me.snowmii.dlss.mrt

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MotionVectorCompatibilityTest {
	@Test
	fun `minecraft and mod-owned shaders stay on the velocity route`() {
		val diagnostics = mutableListOf<String>()
		val compatibility = MotionVectorCompatibility(diagnostics::add)

		assertEquals(
			MotionVectorRoute.VELOCITY_MRT,
			compatibility.observe(
				pipeline(
					"minecraft:pipeline/terrain_solid",
					shader("minecraft:core/terrain", "minecraft"),
					shader("mc-dlss:core/terrain_velocity", "mc-dlss"),
				),
			),
		)
		assertTrue(diagnostics.isEmpty())
	}

	@Test
	fun `foreign shader latches camera-only once and names the incompatible source`() {
		val diagnostics = mutableListOf<String>()
		val compatibility = MotionVectorCompatibility(diagnostics::add)
		val foreign = pipeline(
			"example:pipeline/waving_terrain",
			shader("example:core/waving_terrain", "example"),
			shader("minecraft:core/terrain", "minecraft"),
		)

		assertEquals(MotionVectorRoute.CAMERA_ONLY, compatibility.observe(foreign))
		assertEquals(MotionVectorRoute.CAMERA_ONLY, compatibility.observe(foreign))
		assertEquals(
			MotionVectorRoute.CAMERA_ONLY,
			compatibility.observe(
				pipeline(
					"minecraft:pipeline/terrain_solid",
					shader("minecraft:core/terrain", "minecraft"),
					shader("minecraft:core/terrain", "minecraft"),
				),
			),
		)
		assertEquals(1, diagnostics.size)
		assertTrue(diagnostics.single().contains("example:pipeline/waving_terrain"))
		assertTrue(diagnostics.single().contains("example:core/waving_terrain"))
		assertTrue(diagnostics.single().contains("camera-only"))
		assertEquals(foreign, compatibility.firstForeignPipeline)
	}

	@Test
	fun `diagnostic failure cannot turn custom shader fallback into a render throw`() {
		val compatibility = MotionVectorCompatibility { error("broken diagnostic sink") }

		assertDoesNotThrow {
			compatibility.observe(
				pipeline(
					"custom:pipeline/world",
					shader("custom:core/world", "custom"),
					shader("custom:core/world", "custom"),
				),
			)
		}
		assertEquals(MotionVectorRoute.CAMERA_ONLY, compatibility.selectedRoute)
	}

	private fun pipeline(id: String, vararg shaders: MotionVectorShader) =
		MotionVectorPipeline(id, shaders.toList())

	private fun shader(id: String, owner: String) = MotionVectorShader(id, owner)
}
