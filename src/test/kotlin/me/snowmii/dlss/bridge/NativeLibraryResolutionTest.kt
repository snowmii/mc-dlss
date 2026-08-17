package me.snowmii.dlss.bridge
import me.snowmii.streamline.ExtensionBootstrap
import me.snowmii.dlss.config.ModConfig

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import me.snowmii.dlss.NativeBridge
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The native-library path is injected once by the mod at `McDlss.onInitialize` from its own
 * config, strictly before any `ExtensionBootstrap` seam runs. These pin that injected-path
 * seam: a non-null injected path wins outright, and a null injection — what the mod's config
 * parse produces for a missing or blank `mc.dlss.native-library` property — falls through to
 * the packaged resource staged on the test classpath by processResources.
 */
@NativeBridge
class NativeLibraryResolutionTest {
	@AfterEach
	fun resetInjectedPath() {
		ExtensionBootstrap.setNativeLibraryPath(null)
	}

	@Test
	fun `an injected explicit path overrides every other source`() {
		val requested = Path.of("D:/somewhere/else/mc_dlss.dll")
		ExtensionBootstrap.setNativeLibraryPath(requested)
		val expectedLibrary = requested.toAbsolutePath()
		assertEquals(expectedLibrary, ExtensionBootstrap.nativeLibrary(), "the injected path wins")
	}

	@Test
	fun `a blank property falls through instead of resolving to the working directory`() {
		// The property→path read is mod wiring now: ModConfig drops whitespace-only values, so
		// the mod injects null for a blank property and the SDK falls through to the packaged
		// resource rather than resolving the blank string against the working directory.
		val properties = Properties().apply { setProperty(ModConfig.NATIVE_LIBRARY_PROPERTY, "   ") }
		ExtensionBootstrap.setNativeLibraryPath(ModConfig.from(properties).startupConfig.nativeLibraryPath)
		val resolved = ExtensionBootstrap.nativeLibrary()
		assertTrue(resolved.endsWith(Path.of("mc_dlss.dll")), "resolved to $resolved")
		assertTrue(Files.isRegularFile(resolved), "resolved path must exist: $resolved")
	}

	@Test
	fun `resolution finds a real packaged library without any injected path`() {
		val resolved = ExtensionBootstrap.nativeLibrary()
		assertTrue(Files.isRegularFile(resolved), "resolved path must exist: $resolved")
	}
}
