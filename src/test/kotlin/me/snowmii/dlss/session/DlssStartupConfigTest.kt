package me.snowmii.dlss.session
import me.snowmii.dlss.SRMode
import me.snowmii.streamline.Dimensions
import me.snowmii.dlss.client.ModConfig

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.Properties

class DlssStartupConfigTest {
	@Test
	fun `defaults target fixed output and quality mode`() {
		val config = ModConfig.from(Properties()).startupConfig

		assertTrue(config.enabled)
		assertEquals(SRMode.QUALITY, config.qualityMode)
		assertEquals(Dimensions(2560, 1440), config.outputDimensions)
		assertTrue(config.warnings.isEmpty())
	}

	// Mode selection and NGX values for all six modes are QualityModePresetTest's.

	@Test
	fun `startup properties select all supported modes and paths`() {
		val properties = Properties().apply {
			setProperty(ModConfig.ENABLED_PROPERTY, "off")
			setProperty(ModConfig.MODE_PROPERTY, "PERFORMANCE")
			setProperty(ModConfig.OUTPUT_WIDTH_PROPERTY, "1920")
			setProperty(ModConfig.OUTPUT_HEIGHT_PROPERTY, "1080")
			setProperty(ModConfig.SDK_PATH_PROPERTY, "C:/NVIDIA/DLSS")
			setProperty(ModConfig.NATIVE_LIBRARY_PROPERTY, "C:/mc-dlss/mc_dlss.dll")
			setProperty(ModConfig.DATA_PATH_PROPERTY, "C:/mc-dlss/data")
		}

		val config = ModConfig.from(properties).startupConfig

		assertFalse(config.enabled)
		assertEquals(SRMode.PERFORMANCE, config.qualityMode)
		assertEquals(Dimensions(1920, 1080), config.outputDimensions)
		assertEquals(Path.of("C:/NVIDIA/DLSS"), config.sdkPath)
		assertEquals(Path.of("C:/mc-dlss/mc_dlss.dll"), config.nativeLibraryPath)
		assertEquals(Path.of("C:/mc-dlss/data"), config.dataPath)
	}

	@Test
	fun `invalid path does not abort startup configuration`() {
		val properties = Properties().apply {
			setProperty(ModConfig.SDK_PATH_PROPERTY, "bad\u0000path")
		}

		val config = ModConfig.from(properties).startupConfig

		assertNull(config.sdkPath)
		assertEquals(listOf("${ModConfig.SDK_PATH_PROPERTY}=bad\u0000path is invalid; ignoring path"), config.warnings)
	}

	@Test
	fun `invalid startup properties fall back with warnings`() {
		val properties = Properties().apply {
			setProperty(ModConfig.ENABLED_PROPERTY, "maybe")
			setProperty(ModConfig.MODE_PROPERTY, "ultra")
			setProperty(ModConfig.OUTPUT_WIDTH_PROPERTY, "0")
			setProperty(ModConfig.OUTPUT_HEIGHT_PROPERTY, "not-a-number")
		}

		val config = ModConfig.from(properties).startupConfig

		assertTrue(config.enabled)
		assertEquals(SRMode.QUALITY, config.qualityMode)
		assertEquals(Dimensions(2560, 1440), config.outputDimensions)
		assertEquals(4, config.warnings.size)
	}
}
