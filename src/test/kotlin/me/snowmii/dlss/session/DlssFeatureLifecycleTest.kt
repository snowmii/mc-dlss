package me.snowmii.dlss.session

import me.snowmii.dlss.nativeSource
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DlssFeatureLifecycleTest {
	private val api = nativeSource("mc_dlss_api.cpp")
	private val sl = nativeSource("internal/sl_dlss.cpp")

	@Test
	fun closeAndResetReleaseOnlyModuleOwnedResources() {
		// Reset releases the module-owned images and the retained frame token and keeps the
		// session ready; it owns no NGX object to release. The shutdown_state teardown order
		// itself is pinned by SrOnStreamlineTest, which owns the SL retirement.
		val reset = api.substringAfter("mc_dlss_reset").substringBefore("mc_dlss_close")
		assertTrue(reset.contains("release_images()"))
		assertTrue(reset.contains("invalidate_frame_eligibility()"))
		val eligibility = sl.substringAfter("void invalidate_frame_eligibility").substringBefore("int32_t record_present_handoff")
		assertTrue(eligibility.contains("g_state.frameToken = nullptr"))
		assertTrue(!reset.contains("release_feature"))

		// Close hands the whole session to the same module-owned teardown.
		val close = api.substringAfter("mc_dlss_close")
		assertTrue(close.contains("shutdown_state()"))
	}
}
