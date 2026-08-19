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

/**
 * Streamline's manual-hook Vulkan integration activates against a live device.
 *
 * Two halves: (1) native activation - mc_dlss_activate_vulkan_proxies hands the live instance
 * / physical device / device / graphics queue layout of a headless Vulkan context to
 * slSetVulkanInfo and repeats success on the same layout; (2) the redirect seam -
 * org.lwjgl.vulkan.libname is pointed at the staged sl.interposer.dll, which is how
 * Minecraft's Vulkan loading routes through SL's proxies.
 *
 * After the activation assertions the test records the activated tuple through
 * mc_dlss_initialize so close runs slShutdown while the device is still alive.
 */
@NativeBridge
class StreamlineProxyActivationTest {

	@Test
	fun `activation returns success against live headless device and repeats success`(
		@TempDir dataPath: Path,
	) {
		// Runs slInit through the real bridge (idempotent, survives bridge close), the same
		// pre-Vulkan bootstrap Minecraft's instance seam performs.
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
				// The fixture creates exactly one queue in the graphics family, so Streamline's
				// own queues start at index 1 - right after the host's. The graphics family is
				// compute-capable on this workstation, so the compute queue layout is the same
				// family with the same host count.
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
				// Idempotent: the same seven values must not re-call slSetVulkanInfo.
				assertEquals(StreamlineSession.SUCCESS_RESULT, layout(), "repeated activation must succeed")

				// Record the activated tuple so close runs slShutdown while the device is
				// still alive. The bridge closes inside the fixture's scope.
				SrLiveSession.recordActivatedSession(bridge, fixture, dataPath)
			}
		}
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
			// Shared test JVM: Vulkan may not be loaded yet, and a stale property would redirect
			// every later test's Vulkan loading into the interposer.
			System.clearProperty("org.lwjgl.vulkan.libname")
		}
	}

	/**
	 * The first queue family with the graphics bit - exactly the family [HeadlessVulkanFixture]
	 * creates its single queue in (it exposes no accessor for it).
	 */
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
