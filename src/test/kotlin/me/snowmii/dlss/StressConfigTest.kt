package me.snowmii.dlss

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
		val config = DlssStressConfig.from(Properties())

		assertFalse(config.enabled)
	}

	@Test
	fun `a session names its own workload`() {
		val properties = Properties().apply {
			setProperty(DlssStressConfig.ENABLED_PROPERTY, "true")
			setProperty(DlssStressConfig.STEPS_PROPERTY, "96")
			setProperty(DlssStressConfig.OCTAVES_PROPERTY, "7")
			setProperty(DlssStressConfig.GODRAYS_PROPERTY, "32")
			setProperty(DlssStressConfig.INTENSITY_PROPERTY, "1.5")
		}

		val config = DlssStressConfig.from(properties)

		assertTrue(config.enabled)
		assertEquals(96, config.steps)
		assertEquals(7, config.octaves)
		assertEquals(32, config.godrayTaps)
		assertEquals(1.5f, config.intensity)
	}

	@Test
	fun `a workload beyond what the shader loops for is clamped rather than refused`() {
		val properties = Properties().apply {
			setProperty(DlssStressConfig.STEPS_PROPERTY, "100000")
			setProperty(DlssStressConfig.OCTAVES_PROPERTY, "40")
			setProperty(DlssStressConfig.GODRAYS_PROPERTY, "-8")
		}

		val config = DlssStressConfig.from(properties)

		// The shader's own loop bounds; a request past them would silently do less work than the
		// number the reviewer wrote down, so the number is corrected here instead.
		assertEquals(192, config.steps)
		assertEquals(8, config.octaves)
		assertEquals(0, config.godrayTaps)
	}

	@Test
	fun `an unreadable value leaves the default workload in place`() {
		val properties = Properties().apply {
			setProperty(DlssStressConfig.ENABLED_PROPERTY, "maybe")
			setProperty(DlssStressConfig.STEPS_PROPERTY, "lots")
		}

		val config = DlssStressConfig.from(properties)

		assertFalse(config.enabled)
		assertEquals(64, config.steps)
	}

	@Test
	fun `the reconstruction sign is a property rather than a rebuild`() {
		val flipped = Properties().apply { setProperty(DlssStressConfig.FLIP_Y_PROPERTY, "true") }

		assertEquals(1.0f, DlssStressConfig.from(Properties()).ndcYSign)
		assertEquals(-1.0f, DlssStressConfig.from(flipped).ndcYSign)
	}
}
