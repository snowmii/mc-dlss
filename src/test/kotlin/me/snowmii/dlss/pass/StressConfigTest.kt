package me.snowmii.dlss.pass
import me.snowmii.dlss.config.ModConfig

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Properties

/**
 * The stress pass reads its own properties and never changes what a session renders by default.
 *
 * The load exists to make DLSS measurable, so the one thing that must not drift is that a build
 * carrying it renders exactly like a build without it until someone asks otherwise: a benchmark
 * whose baseline quietly includes the instrument measures nothing.
 */
class StressConfigTest {
	@Test
	fun `stress is off unless a session asks for it`() {
		val config = StressConfig.from(Properties())

		assertFalse(config.enabled)
	}

	@Test
	fun `a session names its own workload`() {
		val properties = Properties().apply {
			setProperty(ModConfig.STRESS_ENABLED_PROPERTY, "true")
			setProperty(ModConfig.STRESS_STEPS_PROPERTY, "96")
			setProperty(ModConfig.STRESS_OCTAVES_PROPERTY, "7")
			setProperty(ModConfig.STRESS_GODRAYS_PROPERTY, "32")
			setProperty(ModConfig.STRESS_INTENSITY_PROPERTY, "1.5")
		}

		val config = StressConfig.from(properties)

		assertTrue(config.enabled)
		assertEquals(96, config.steps)
		assertEquals(7, config.octaves)
		assertEquals(32, config.godrayTaps)
		assertEquals(1.5f, config.intensity)
	}

	@Test
	fun `a workload beyond what the shader loops for is clamped rather than refused`() {
		val properties = Properties().apply {
			setProperty(ModConfig.STRESS_STEPS_PROPERTY, "100000")
			setProperty(ModConfig.STRESS_OCTAVES_PROPERTY, "40")
			setProperty(ModConfig.STRESS_GODRAYS_PROPERTY, "-8")
		}

		val config = StressConfig.from(properties)

		// The shader's own loop bounds; a request past them would silently do less work than the
		// number the reviewer wrote down, so the number is corrected here instead.
		assertEquals(192, config.steps)
		assertEquals(8, config.octaves)
		assertEquals(0, config.godrayTaps)
	}

	@Test
	fun `an unreadable value leaves the default workload in place`() {
		val properties = Properties().apply {
			setProperty(ModConfig.STRESS_ENABLED_PROPERTY, "maybe")
			setProperty(ModConfig.STRESS_STEPS_PROPERTY, "lots")
		}

		val config = StressConfig.from(properties)

		assertFalse(config.enabled)
		assertEquals(24, config.steps)
	}

	@Test
	fun `the reconstruction sign is a property rather than a rebuild`() {
		val flipped = Properties().apply { setProperty(ModConfig.STRESS_FLIP_Y_PROPERTY, "true") }

		assertEquals(1.0f, StressConfig.from(Properties()).ndcYSign)
		assertEquals(-1.0f, StressConfig.from(flipped).ndcYSign)
	}
}
