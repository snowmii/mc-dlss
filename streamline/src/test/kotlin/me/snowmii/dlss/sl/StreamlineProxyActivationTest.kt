package me.snowmii.dlss.sl

import java.nio.file.Files
import java.nio.file.Path
import me.snowmii.dlss.NativeBridge
import me.snowmii.streamline.ExtensionBootstrap
import me.snowmii.dlss.bridge.HeadlessVulkanFixture
import me.snowmii.streamline.NativeTestAccess
import me.snowmii.streamline.StreamlineSession
import me.snowmii.streamline.StreamlineVulkanProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VkInstance
import org.lwjgl.vulkan.VkInstanceCreateInfo
import org.lwjgl.vulkan.VkPhysicalDevice
import org.lwjgl.vulkan.VkQueueFamilyProperties

@NativeBridge
class StreamlineProxyActivationTest {
	@Test
	fun `bootstrap without a session shuts down explicitly and can start again`() {
		ExtensionBootstrap.queryInstanceExtensions()
		try {
			NativeTestAccess.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
				assertEquals(StreamlineSession.SUCCESS_RESULT, bridge.shutdownStreamline())
				org.junit.jupiter.api.Assertions.assertThrows(me.snowmii.streamline.StreamlineException::class.java) {
					bridge.queryInstanceExtensions()
				}
				assertEquals(StreamlineSession.SUCCESS_RESULT,
					bridge.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()))
				assertTrue(bridge.queryInstanceExtensions().isNotEmpty())
			}
		} finally {
			ExtensionBootstrap.shutdown()
		}
	}

	@Test
	fun `activation returns success against live headless device and repeats success`(
		@TempDir dataPath: Path,
	) {
		val instanceExtensions = ExtensionBootstrap.queryInstanceExtensions()
		HeadlessVulkanFixture(instanceExtensions) { instance, physicalDevice ->
			val extensions = mutableListOf<String>()
			ExtensionBootstrap.addDeviceExtensions(extensions, instance, physicalDevice)
			extensions
		}.use { fixture ->
			NativeTestAccess.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
				assertEquals(
					StreamlineSession.SUCCESS_RESULT,
					bridge.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
				)

				val graphicsFamily = graphicsQueueFamilyOf(fixture)
				val hostQueueCount = 1
				val layout = {
					bridge.activateVulkanProxies(
						fixture.instanceAddress(),
						fixture.physicalDeviceAddress(),
						fixture.deviceAddress(),
						graphicsFamily,
						hostQueueCount,
						graphicsFamily,
						hostQueueCount,
					)
				}
				assertEquals(StreamlineSession.SUCCESS_RESULT, layout(), "first activation must succeed")
				assertEquals(StreamlineSession.SUCCESS_RESULT, layout(), "repeated activation must succeed")

				SrLiveSession.recordActivatedSession(bridge, fixture, dataPath)
			}
		}
	}

	@Test
	fun `Streamline present-chain and surface proxies bind when staged runtime is present`() {
		assertTrue(
			me.snowmii.streamline.StreamlineVulkanProxies.available(),
			"Vulkan proxies must bind from staged sl.interposer.dll",
		)
	}

	@Test
	fun `redirect seam points LWJGL Vulkan at the staged interposer`() {
		try {
			val path = StreamlineVulkanProvider.redirectToInterposer()
			assertTrue(Files.isRegularFile(path), "staged interposer must be a regular file")
			assertEquals("sl.interposer.dll", path.fileName.toString())
			val expectedLibname = path.toAbsolutePath().toString()
			assertEquals(
				expectedLibname,
				System.getProperty("org.lwjgl.vulkan.libname"),
				"LWJGL must be pointed at the absolute interposer path",
			)
		} finally {
			System.clearProperty("org.lwjgl.vulkan.libname")
		}
	}

	private fun graphicsQueueFamilyOf(fixture: HeadlessVulkanFixture): Int {
		MemoryStack.stackPush().use { stack ->
			val instance = VkInstance(fixture.instanceAddress(), VkInstanceCreateInfo.calloc(stack))
			val physicalDevice = VkPhysicalDevice(fixture.physicalDeviceAddress(), instance)
			val familyCount = stack.callocInt(1)
			VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, familyCount, null)
			val families = VkQueueFamilyProperties.calloc(familyCount.get(0), stack)
			VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, familyCount, families)
			for (index in 0 until familyCount.get(0)) {
				if ((families.get(index).queueFlags() and VK10.VK_QUEUE_GRAPHICS_BIT) != 0) {
					return index
				}
			}
		}
		error("No graphics queue family on the fixture's physical device")
	}
}
