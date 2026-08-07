package me.snowmii.dlss.bridge

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pre-creation NGX extension requirements through the real compiled DLL and Java 25 FFM.
 *
 * Mirrors the fix the native bridge exposes: query the exact NGX-required Vulkan instance
 * and device extension names before the application's instance/device are created, so they
 * can be enabled by the bootstrap mixins. Instance query needs no Vulkan objects; device
 * query needs a live instance + physical device (which exist before device creation).
 */
class DlssExtensionRequirementsTest {
	@Test
	fun `queries exact NGX instance and device extension requirements pre-creation`() {
		Native.open(Path.of("build/native/mc_dlss.dll")).use { native ->
			val instanceExtensions = native.queryInstanceExtensions()
			assertFalse(instanceExtensions.isEmpty(), "NGX must require at least one instance extension")
			assertEquals(instanceExtensions.distinct(), instanceExtensions)
			assertTrue(instanceExtensions.all { it.startsWith("VK_") }, "instance names must be VK_ extensions: $instanceExtensions")

			HeadlessVulkanFixture().use { vulkan ->
				val deviceExtensions = native.queryDeviceExtensions(
					vulkan.instanceAddress(),
					vulkan.physicalDeviceAddress(),
				)
				assertFalse(deviceExtensions.isEmpty(), "NGX must require at least one device extension")
				assertEquals(deviceExtensions.distinct(), deviceExtensions)
				assertTrue(deviceExtensions.all { it.startsWith("VK_") }, "device names must be VK_ extensions: $deviceExtensions")
			}
		}
	}
}
