package me.snowmii.dlss.client

import me.snowmii.dlss.readout.SessionReadout
import me.snowmii.streamline.FgState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SessionReadoutTest {
	@Test
	fun `fg monitor suffix spells status and fence and vanishes without state`() {
		assertEquals(
			", fg=status=0 fence=3",
			SessionReadout.fgMonitorSuffix(FgState(0, 2, 3, 0L)),
		)
		assertEquals("", SessionReadout.fgMonitorSuffix(null))
	}

	@Test
	fun `fg presented suffix is app fps times presentations and hides at 1x`() {
		assertEquals(" fg 120", SessionReadout.fgPresentedFpsSuffix(60, FgState(0, 2, 3, 0L)))
		assertEquals("", SessionReadout.fgPresentedFpsSuffix(60, FgState(0, 1, 3, 0L)))
	}
}
