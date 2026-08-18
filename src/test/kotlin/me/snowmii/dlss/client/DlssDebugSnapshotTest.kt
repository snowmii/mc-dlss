package me.snowmii.dlss.client

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DlssDebugSnapshotTest {
	@Test
	fun `F3 snapshot keeps fps FG and motion-compat lines and drops the rest`() {
		DlssDebugSnapshot.clear()
		DlssDebugSnapshot.record("DLSS first world phase: route=DLSS")
		DlssDebugSnapshot.record("DLSS acceptance record\n  quality-mode=quality\n  render-preset=k")
		DlssDebugSnapshot.record("DLSS first evaluation: recorded=true")
		DlssDebugSnapshot.record("DLSS output resolution: 1080p -> 1440p")
		DlssDebugSnapshot.record("DLSS motion vectors: camera-only fallback")
		DlssDebugSnapshot.record("stress on | 24 steps")
		DlssDebugSnapshot.record("DLSS world frame rate: 12.0 fps over 10 frames, route=VANILLA, world=main-target, gpu=unmeasured")
		DlssDebugSnapshot.record(
			"DLSS world frame rate: 72.2 fps over 361 frames, route=DLSS, world=1280x720, " +
				"gpu=total=0.89ms motion=0.01ms, accum=phases=32/361 resets=0/361 jitter=0.063,-0.463",
		)
		DlssDebugSnapshot.record("Frame generation resumed: slDLSSGGetState status=0x0 (eDLSSGStatusOk).")

		val lines = DlssDebugSnapshot.lines()
		assertTrue(lines.any { it.contains("motion: camera-only fallback") })
		assertTrue(lines.any { it == "DLSS 72 fps  DLSS  gpu 0.89ms  resets 0" })
		assertTrue(lines.any { it == "FG resumed" })
		assertFalse(lines.any { it.contains("old") })
		assertFalse(lines.any { it.contains("route=DLSS") })
		assertFalse(lines.any { it.contains("quality-mode=quality") })
		assertFalse(lines.any { it.contains("recorded=true") })
		assertFalse(lines.any { it.contains("1080p") })
		assertFalse(lines.any { it.contains("24 steps") })
	}
}
