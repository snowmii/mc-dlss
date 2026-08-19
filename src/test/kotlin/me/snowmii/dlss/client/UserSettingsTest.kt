package me.snowmii.dlss.client

import me.snowmii.dlss.SRMode
import me.snowmii.dlss.SRModelPreset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

class UserSettingsTest {
	@Test
	fun `user toggle does not suppress runtime construction but an explicit launch override still can`() {
		val userSettings = ModConfig.user.withSystemOverrides(Properties())
		assertNull(userSettings.getProperty(ModConfig.ENABLED_PROPERTY))
		assertEquals(ModConfig.user.qualityMode.propertyValue, userSettings.getProperty(ModConfig.MODE_PROPERTY))
		assertEquals(ModConfig.user.renderPreset.propertyValue, userSettings.getProperty(ModConfig.PRESET_PROPERTY))

		val launchOverride = Properties().apply { setProperty(ModConfig.ENABLED_PROPERTY, "false") }
		assertEquals(
			"false",
			ModConfig.user.withSystemOverrides(launchOverride).getProperty(ModConfig.ENABLED_PROPERTY),
		)
	}

	@Test
	fun `file-backed settings round-trip as typed mode and preset`() {
		withSettingsFile { file ->
			val written = UserSettings(file)
			written.enabled = false
			written.qualityMode = SRMode.DLAA
			written.renderPreset = SRModelPreset.K
			written.frameGeneration = true

			val text = Files.readString(file)
			assertTrue("\"enabled\": false" in text)
			assertTrue("\"qualityMode\": \"dlaa\"" in text)

			val read = UserSettings(file)
			assertFalse(read.enabled)
			assertEquals(SRMode.DLAA, read.qualityMode)
			assertEquals(SRModelPreset.K, read.renderPreset)
			assertTrue(read.frameGeneration)
		}
	}

	@Test
	fun `existing pretty-printed json still loads`() {
		withSettingsFile(
			"""
			{
			  "enabled": false,
			  "qualityMode": "performance",
			  "renderPreset": "l",
			  "frameGeneration": true
			}
			""".trimIndent(),
		) { file ->
			val read = UserSettings(file)
			assertFalse(read.enabled)
			assertEquals(SRMode.PERFORMANCE, read.qualityMode)
			assertEquals(SRModelPreset.L, read.renderPreset)
			assertTrue(read.frameGeneration)
		}
	}

	@Test
	fun `invalid json keeps defaults`() {
		withSettingsFile("not json") { file ->
			val read = UserSettings(file)
			assertTrue(read.enabled)
			assertEquals(SRMode.QUALITY, read.qualityMode)
			assertEquals(SRModelPreset.M, read.renderPreset)
			assertFalse(read.frameGeneration)
		}
	}

	private fun withSettingsFile(contents: String? = null, block: (Path) -> Unit) {
		val file = Files.createTempFile("mc-dlss", ".json")
		try {
			if (contents != null) {
				Files.writeString(file, contents)
			}
			block(file)
		} finally {
			Files.deleteIfExists(file)
			Files.deleteIfExists(file.resolveSibling("${file.fileName}.tmp"))
		}
	}
}
