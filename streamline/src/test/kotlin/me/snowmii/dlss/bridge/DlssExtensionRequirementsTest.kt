package me.snowmii.dlss.bridge

import java.nio.file.Path
import me.snowmii.dlss.NativeBridge
import me.snowmii.streamline.ExtensionBootstrap
import me.snowmii.streamline.NativeTestAccess
import me.snowmii.streamline.StreamlineSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pre-creation Streamline extension requirements through the real compiled DLL and Java 25 FFM.
 *
 * Queries the exact Streamline-required Vulkan instance and device extension names before the
 * application's instance/device are created, so the bootstrap mixins can enable them. Instance
 * query needs no Vulkan objects; device query needs a live instance + physical device. The
 * bridge bootstraps Streamline first, as the production seams do: a query before bootstrap is
 * refused.
 */
@NativeBridge
class DlssExtensionRequirementsTest {
	@Test
	fun `queries exact Streamline instance and device extension requirements pre-creation`() {
		NativeTestAccess.open(Path.of("build/native/mc_dlss.dll")).use { native ->
			assertEquals(
				StreamlineSession.SUCCESS_RESULT,
				native.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
			)
			val instanceExtensions = native.queryInstanceExtensions()
			assertFalse(instanceExtensions.isEmpty(), "Streamline must require at least one instance extension")
			assertEquals(instanceExtensions.distinct(), instanceExtensions)
			assertTrue(instanceExtensions.all { it.startsWith("VK_") }, "instance names must be VK_ extensions: $instanceExtensions")

			HeadlessVulkanFixture().use { vulkan ->
				val deviceExtensions = native.queryDeviceExtensions(
					vulkan.instanceAddress(),
					vulkan.physicalDeviceAddress(),
				)
				assertFalse(deviceExtensions.isEmpty(), "Streamline must require at least one device extension")
				assertEquals(deviceExtensions.distinct(), deviceExtensions)
				assertTrue(deviceExtensions.all { it.startsWith("VK_") }, "device names must be VK_ extensions: $deviceExtensions")
			}
		}
	}
}
