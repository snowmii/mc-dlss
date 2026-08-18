package me.snowmii.dlss.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Properties

class ClientConfigTest {
	@Test
	fun `user toggle does not suppress runtime construction but an explicit launch override still can`() {
		val userSettings = ClientConfig.INSTANCE.withSystemOverrides(Properties())
		assertNull(userSettings.getProperty(ModConfig.ENABLED_PROPERTY))

		val launchOverride = Properties().apply { setProperty(ModConfig.ENABLED_PROPERTY, "false") }
		assertEquals(
			"false",
			ClientConfig.INSTANCE.withSystemOverrides(launchOverride).getProperty(ModConfig.ENABLED_PROPERTY),
		)
	}
}
