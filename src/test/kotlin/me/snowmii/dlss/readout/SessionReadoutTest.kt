package me.snowmii.dlss.readout

import me.snowmii.dlss.bridge.FgState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SessionReadoutTest {
	/**
	 * The DLSS-G monitor suffix is the reviewer's live generation oracle: it must spell the
	 * plugin's presented count as a rate over the sample window, the status word, and the fence,
	 * and it must vanish when the plugin reports nothing. A presented rate near twice the app's
	 * frame rate on the same line is the 2x-generation proof the rung's live claim rests on.
	 */
	@Test
	fun `fg monitor suffix spells presented rate status and fence and vanishes without state`() {
		// Streamline reports two presentations per app frame at 2x generation.
		assertEquals(
			", fg=presented=120.0 status=0 fence=3",
			SessionReadout.fgMonitorSuffix(
				FgState(status = 0, numFramesPresented = 2, lastPresentInputsProcessingFenceValue = 3, inputsProcessingCompletionFence = 0L),
				60.0,
			),
		)
		assertEquals("", SessionReadout.fgMonitorSuffix(null, 60.0))
	}
}
