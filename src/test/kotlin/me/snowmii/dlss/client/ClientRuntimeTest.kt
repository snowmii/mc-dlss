package me.snowmii.dlss.client

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The type system already makes a read-only call site unable to compile a creating call; this
 * test proves the runtime behavior behind it, off the render thread, where a real build would
 * not be allowed to run.
 */
class ClientRuntimeTest {
	@Test
	fun `active reads never build the DLSS path and the render loop is the only builder`() {
		// Reads through ActiveView must not trigger initialization: no phase appears, and
		// a subsequent render-loop call must still be the first builder.
		assertNull(ClientRuntime.active().activeWorldPhase(), "reads must not initialize the runtime")
		assertNull(ClientRuntime.active().activeUiPhase())
		assertNull(ClientRuntime.active().activeControls())

		val sessionOpener = ClientRuntime.sessionOpener
		ClientRuntime.sessionOpener = { null }
		try {
			ClientRuntime.renderLoop().worldPhase()
		} finally {
			ClientRuntime.sessionOpener = sessionOpener
		}

		// Shutdown latches the seam before Vulkan device teardown: a render call still in
		// flight must not reopen the native bridge against a device that is going away.
		ClientRuntime.shutdown()

		assertNull(ClientRuntime.active().activeWorldPhase())
		assertNull(ClientRuntime.active().activeUiPhase())
		assertNull(ClientRuntime.active().activeControls())

		ClientRuntime.renderLoop().worldPhase()

		assertNull(ClientRuntime.active().activeWorldPhase(), "a shut-down runtime never rebuilds")
	}
}
