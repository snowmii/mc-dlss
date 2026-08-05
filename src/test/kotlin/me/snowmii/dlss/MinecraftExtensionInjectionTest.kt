package me.snowmii.dlss

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MinecraftExtensionInjectionTest {
	@Test
	fun `production bootstrap seams supply exact NGX requirements before Vulkan creation`() {
		val repository = Path.of("").toAbsolutePath()
		assertTrue(Files.isRegularFile(repository.resolve("build/native/mc_dlss.dll")))

		val instanceExtensions = DlssExtensionBootstrap.queryInstanceExtensions()
		assertTrue(instanceExtensions.isNotEmpty())
		HeadlessVulkanFixture(instanceExtensions) { instance, physicalDevice ->
			val injected = linkedSetOf<String>()
			DlssExtensionBootstrap.addDeviceExtensions(injected, instance, physicalDevice)
			assertTrue(injected.isNotEmpty())
			injected.toList()
		}.use { fixture ->
			assertTrue(fixture.instanceAddress() != 0L)
			assertTrue(fixture.deviceAddress() != 0L)
		}

		val mixins = repository.resolve("src/main/resources/mc-dlss.mixins.json").readText()
		assertTrue(mixins.contains("VulkanInstanceExtensionMixin"))
		assertTrue(mixins.contains("VulkanBackendExtensionMixin"))
		assertEquals(instanceExtensions.distinct(), instanceExtensions)
	}
}
