package me.snowmii.dlss.session

import me.snowmii.dlss.readNativeSource
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DlssFeatureLifecycleTest {
	private val api = readNativeSource("mc_dlss_api.cpp")

	@Test
	fun `close and reset release only module-owned resources`() {
		// Reset releases the module-owned images and the retained frame token and keeps the
		// session ready. What invalidation actually drops is proved device-free by
		// native/test/frame_eligibility_test.cpp; this scan pins that reset routes through it
		// rather than releasing images and leaving the frame's records standing.
		val reset = api.substringAfter("mc_dlss_reset").substringBefore("mc_dlss_close")
		assertTrue(reset.contains("release_images()"))
		assertTrue(reset.contains("g_state.frameEligibility.invalidate()"))
		assertTrue(!reset.contains("release_feature"))

		// Close hands the whole session to the same module-owned teardown.
		val close = api.substringAfter("mc_dlss_close")
		assertTrue(close.contains("shutdown_state()"))
	}
}
