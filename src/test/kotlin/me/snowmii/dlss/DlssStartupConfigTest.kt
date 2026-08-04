package me.snowmii.dlss

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.Properties

class DlssStartupConfigTest {
	@Test
	fun defaultsTargetFixedOutputAndQualityMode() {
		val config = DlssStartupConfig.from(Properties())

		assertTrue(config.enabled)
		assertEquals(DlssQualityMode.QUALITY, config.qualityMode)
		assertEquals(DlssDimensions(2560, 1440), config.outputDimensions)
		assertTrue(config.warnings.isEmpty())
	}

	@Test
	fun startupModesMapToStableNgxValues() {
		listOf(
			"quality" to DlssQualityMode.QUALITY,
			"balanced" to DlssQualityMode.BALANCED,
			"performance" to DlssQualityMode.PERFORMANCE,
		).forEach { (propertyValue, expectedMode) ->
			val properties = Properties().apply {
				setProperty(DlssStartupConfig.MODE_PROPERTY, propertyValue)
			}

			assertEquals(expectedMode, DlssStartupConfig.from(properties).qualityMode)
		}
	}

	@Test
	fun startupModesExposeNgxQualityValues() {
		assertEquals(2, DlssQualityMode.QUALITY.ngxValue)
		assertEquals(1, DlssQualityMode.BALANCED.ngxValue)
		assertEquals(0, DlssQualityMode.PERFORMANCE.ngxValue)
	}

	@Test
	fun startupPropertiesSelectAllSupportedModesAndPaths() {
		val properties = Properties().apply {
			setProperty(DlssStartupConfig.ENABLED_PROPERTY, "off")
			setProperty(DlssStartupConfig.MODE_PROPERTY, "PERFORMANCE")
			setProperty(DlssStartupConfig.OUTPUT_WIDTH_PROPERTY, "1920")
			setProperty(DlssStartupConfig.OUTPUT_HEIGHT_PROPERTY, "1080")
			setProperty(DlssStartupConfig.SDK_PATH_PROPERTY, "C:/NVIDIA/DLSS")
			setProperty(DlssStartupConfig.NATIVE_LIBRARY_PROPERTY, "C:/mc-dlss/mc_dlss.dll")
			setProperty(DlssStartupConfig.DATA_PATH_PROPERTY, "C:/mc-dlss/data")
		}

		val config = DlssStartupConfig.from(properties)

		assertFalse(config.enabled)
		assertEquals(DlssQualityMode.PERFORMANCE, config.qualityMode)
		assertEquals(DlssDimensions(1920, 1080), config.outputDimensions)
		assertEquals(Path.of("C:/NVIDIA/DLSS"), config.sdkPath)
		assertEquals(Path.of("C:/mc-dlss/mc_dlss.dll"), config.nativeLibraryPath)
		assertEquals(Path.of("C:/mc-dlss/data"), config.dataPath)
	}

	@Test
	fun invalidPathDoesNotAbortStartupConfiguration() {
		val properties = Properties().apply {
			setProperty(DlssStartupConfig.SDK_PATH_PROPERTY, "bad\u0000path")
		}

		val config = DlssStartupConfig.from(properties)

		assertNull(config.sdkPath)
		assertEquals(listOf("${DlssStartupConfig.SDK_PATH_PROPERTY}=bad\u0000path is invalid; ignoring path"), config.warnings)
	}

	@Test
	fun invalidStartupPropertiesFallBackWithWarnings() {
		val properties = Properties().apply {
			setProperty(DlssStartupConfig.ENABLED_PROPERTY, "maybe")
			setProperty(DlssStartupConfig.MODE_PROPERTY, "ultra")
			setProperty(DlssStartupConfig.OUTPUT_WIDTH_PROPERTY, "0")
			setProperty(DlssStartupConfig.OUTPUT_HEIGHT_PROPERTY, "not-a-number")
		}

		val config = DlssStartupConfig.from(properties)

		assertTrue(config.enabled)
		assertEquals(DlssQualityMode.QUALITY, config.qualityMode)
		assertEquals(DlssDimensions(2560, 1440), config.outputDimensions)
		assertEquals(4, config.warnings.size)
	}
}
