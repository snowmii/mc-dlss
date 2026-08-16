package me.snowmii.dlss.client

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Proves the render-loop seam's contract: the [ActiveView] reads never build the DLSS
 * path, and [RenderLoopView] is the only side that does.
 *
 * The type system already makes a read-only call site unable to compile a creating call; this
 * test proves the runtime behavior behind it, off the render thread, where a real build would
 * not be allowed to run.
 */
class ClientRuntimeTest {
	@Test
	fun `active reads never build the DLSS path and the render loop is the only builder`() {
		// No other test in the suite touches the client runtime, so the holder starts unbuilt.
		assertFalse(ClientRuntime.isInitialized, "no other test builds the client runtime")

		ClientRuntime.active().activeWorldPhase()
		ClientRuntime.active().activeControls()

		assertFalse(ClientRuntime.isInitialized, "reads must not initialize the runtime")
		assertNull(ClientRuntime.active().activeWorldPhase())

		ClientRuntime.renderLoop().worldPhase()

		assertTrue(ClientRuntime.isInitialized, "the render loop is the only builder")

		// Shutdown runs from `Minecraft.close()`, ahead of the Vulkan device teardown, and has to
		// leave the seam latched: a render call still in flight on the way out must not open the
		// native bridge again against a device that is already going away.
		ClientRuntime.shutdown()

		assertTrue(ClientRuntime.isInitialized, "shutdown leaves the seam latched")
		assertNull(ClientRuntime.active().activeWorldPhase())
		assertNull(ClientRuntime.active().activeUiPhase())
		assertNull(ClientRuntime.active().activeControls())

		ClientRuntime.renderLoop().worldPhase()

		assertNull(ClientRuntime.active().activeWorldPhase(), "a shut-down runtime never rebuilds")
	}
}
