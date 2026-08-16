package me.snowmii.dlss.bridge
import me.snowmii.dlss.config.ModConfig

import java.nio.file.Files
import java.nio.file.Path
import me.snowmii.dlss.NativeBridge
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The dev client runs with `run/` as its working directory while tests run from the
 * repository root, so a bare relative path resolved differently in each and crashed the
 * client at `VulkanInstance.<init>` with `Cannot open library: ...\run\build\native\mc_dlss.dll`.
 * These pin the resolution order that fixed it.
 */
@NativeBridge
class NativeLibraryResolutionTest {
	@Test
	fun `explicit property overrides every other source`() {
		val requested = Path.of("D:/somewhere/else/mc_dlss.dll")
		withProperty(requested.toString()) {
			val expectedLibrary = requested.toAbsolutePath()
			assertEquals(expectedLibrary, ExtensionBootstrap.nativeLibrary(), "the explicit property wins")
		}
	}

	@Test
	fun `a blank property falls through instead of resolving to the working directory`() {
		withProperty("   ") {
			val resolved = ExtensionBootstrap.nativeLibrary()
			assertTrue(resolved.endsWith(Path.of("mc_dlss.dll")), "resolved to $resolved")
			assertTrue(Files.isRegularFile(resolved), "resolved path must exist: $resolved")
		}
	}

	@Test
	fun `resolution finds a real library without any property set`() {
		withProperty(null) {
			val resolved = ExtensionBootstrap.nativeLibrary()
			assertTrue(Files.isRegularFile(resolved), "resolved path must exist: $resolved")
		}
	}

	private fun withProperty(value: String?, block: () -> Unit) {
		val key = ModConfig.NATIVE_LIBRARY_PROPERTY
		val previous = System.getProperty(key)
		if (value == null) System.clearProperty(key) else System.setProperty(key, value)
		try {
			block()
		} finally {
			if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
		}
	}
}
