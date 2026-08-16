package me.snowmii.dlss.render
import me.snowmii.streamline.Dimensions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Proves the jitter sequence is deterministic, stays inside the pixel, scales its period with
 * the upscale ratio, and expresses one offset in both the units its two consumers need.
 */
class DlssJitterTest {
	private val output = Dimensions(2560, 1440)
	private val quality = Dimensions(1707, 960)
	private val performance = Dimensions(1280, 720)

	@Test
	fun `the sequence is a pure function of its phase`() {
		val first = DlssJitter(quality, output)
		val second = DlssJitter(quality, output)

		val firstRun = List(first.phaseCount) { first.advance() }
		val secondRun = List(second.phaseCount) { second.advance() }

		assertEquals(firstRun, secondRun)
	}

	@Test
	fun `the sequence repeats after exactly one period`() {
		val jitter = DlssJitter(quality, output)

		val period = List(jitter.phaseCount) { jitter.advance() }
		val next = jitter.advance()

		assertEquals(period.first(), next)
		// A period whose entries are not distinct would accumulate fewer samples than it costs.
		assertEquals(jitter.phaseCount, period.distinct().size)
	}

	@Test
	fun `every offset samples inside its own pixel`() {
		val jitter = DlssJitter(performance, output)

		repeat(jitter.phaseCount * 3) {
			val offset = jitter.advance()
			assertTrue(abs(offset.pixelX) <= 0.5f, "pixelX out of pixel: ${offset.pixelX}")
			assertTrue(abs(offset.pixelY) <= 0.5f, "pixelY out of pixel: ${offset.pixelY}")
			// Halton index 0 is exactly 0 in every base; a sequence that used it would put one
			// sample on the pixel corner rather than inside the pixel.
			assertTrue(
				offset.pixelX != -0.5f || offset.pixelY != -0.5f,
				"phase ${offset.index} samples the pixel corner",
			)
		}
	}

	@Test
	fun `the period grows with the square of the upscale ratio`() {
		assertEquals(DlssJitter.BASE_PHASE_COUNT, DlssJitter(output, output).phaseCount)
		// 2560/1280 is exactly 2x, so 8 * 2^2.
		assertEquals(DlssJitter.BASE_PHASE_COUNT * 4, DlssJitter(performance, output).phaseCount)
		assertTrue(
			DlssJitter(quality, output).phaseCount < DlssJitter(performance, output).phaseCount,
			"a smaller upscale ratio must need fewer phases",
		)
	}

	@Test
	fun `the clip offset is the pixel offset in normalized device units`() {
		val jitter = DlssJitter(performance, output)

		val offset = jitter.advance()

		assertEquals(2f * offset.pixelX / performance.width, offset.clipOffsetX)
		assertEquals(2f * offset.pixelY / performance.height, offset.clipOffsetY)
		assertEquals(performance, offset.renderDimensions)
	}

	@Test
	fun `reset restarts the sequence`() {
		val jitter = DlssJitter(quality, output)
		val first = jitter.advance()
		jitter.advance()

		jitter.reset()

		assertEquals(first, jitter.advance())
	}
}
