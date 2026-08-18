package me.snowmii.dlss.client

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SodiumConfigMetadataTest {
	@Test
	fun `Sodium config integration is declared as a Fabric entrypoint`() {
		val stream = requireNotNull(javaClass.getResourceAsStream("/fabric.mod.json"))
		val metadata = stream.reader().use { JsonParser.parseReader(it).asJsonObject }

		assertEquals(
			"me.snowmii.dlss.client.SodiumConfigEntryPoint",
			metadata.getAsJsonObject("entrypoints")
				.getAsJsonArray("sodium:config_api_user")
				.single().asString,
		)
	}
}
