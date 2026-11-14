package me.snowmii.dlss.sl

import java.nio.file.Path
import me.snowmii.dlss.NativeBridge
import me.snowmii.streamline.ExtensionBootstrap
import me.snowmii.dlss.bridge.HeadlessVulkanFixture
import me.snowmii.streamline.NativeTestAccess
import me.snowmii.streamline.StreamlineSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.io.TempDir
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkDeviceCreateInfo
import org.lwjgl.vulkan.VkInstance
import org.lwjgl.vulkan.VkInstanceCreateInfo
import org.lwjgl.vulkan.VkPhysicalDevice

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@NativeBridge
class StreamlineRequirementsMergeTest {

	@Order(1)
	@Test
	fun `SL features12 and 13 surface through the bridge`() {
		ExtensionBootstrap.queryInstanceExtensions()
		NativeTestAccess.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
			assertEquals(
				StreamlineSession.SUCCESS_RESULT,
				bridge.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
			)
			val features12 = bridge.queryDeviceFeatures12()
			val features13 = bridge.queryDeviceFeatures13()
			assertTrue(
				features12.containsAll(listOf("timelineSemaphore", "descriptorIndexing", "bufferDeviceAddress")),
				"SL-required Vulkan 1.2 features must include the observed set, got $features12",
			)
			assertTrue(
				features13.contains("synchronization2"),
				"SL-required Vulkan 1.3 features must include synchronization2, got $features13",
			)
		}
	}

	@Order(2)
	@Test
	fun `DLSS_G queue requirements surface through the bridge`() {
		ExtensionBootstrap.queryInstanceExtensions()
		NativeTestAccess.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
			assertEquals(
				StreamlineSession.SUCCESS_RESULT,
				bridge.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
			)
			val requirements = bridge.queryQueueRequirements()
			assertEquals(1, requirements.graphicsQueues, "summed extra graphics queues")
			assertEquals(2, requirements.computeQueues, "summed extra compute queues")
			assertEquals(1, requirements.opticalFlowQueues, "summed extra optical-flow queues")
		}
	}

	@Order(4)
	@Test
	fun `merged queue layout activates proxies against a device holding SL queues`(
		@TempDir dataPath: Path,
	) {
		val instanceExtensions = ExtensionBootstrap.queryInstanceExtensions()
		val requirements = NativeTestAccess.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
			assertEquals(
				StreamlineSession.SUCCESS_RESULT,
				bridge.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
			)
			bridge.queryQueueRequirements()
		}

		val graphicsFamily = probeGraphicsQueueFamily()
		val extras = requirements.graphicsQueues + requirements.computeQueues
		HeadlessVulkanFixture(
			instanceExtensions,
			{ instance, physicalDevice ->
				val extensions = mutableListOf<String>()
				ExtensionBootstrap.addDeviceExtensions(extensions, instance, physicalDevice)
				extensions
			},
			false,
			mapOf(graphicsFamily to extras),
		).use { fixture ->
			NativeTestAccess.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
				assertEquals(
					StreamlineSession.SUCCESS_RESULT,
					bridge.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
				)
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
				assertEquals(
					StreamlineSession.SUCCESS_RESULT,
					layout(),
					"activation must succeed against the merged queue layout",
				)
				assertEquals(
					StreamlineSession.SUCCESS_RESULT,
					layout(),
					"repeated activation must succeed",
				)

				assertNotNull(
					vkGetDeviceQueue(fixture, graphicsFamily, hostQueueCount),
					"first SL-owned graphics queue must exist on the merged device",
				)
				assertNotNull(
					vkGetDeviceQueue(fixture, graphicsFamily, hostQueueCount + extras - 1),
					"last SL-owned queue must exist on the merged device",
				)
				assertNull(
					vkGetDeviceQueue(fixture, graphicsFamily, hostQueueCount + extras),
					"the device must not exceed the merged queue count",
				)

				SrLiveSession.recordActivatedSession(bridge, fixture, dataPath)
			}
		}
	}

	private fun vkGetDeviceQueue(fixture: HeadlessVulkanFixture, family: Int, index: Int): Long? {
		MemoryStack.stackPush().use { stack ->
			val instance = VkInstance(fixture.instanceAddress(), VkInstanceCreateInfo.calloc(stack))
			val physicalDevice = VkPhysicalDevice(fixture.physicalDeviceAddress(), instance)
			val device = VkDevice(fixture.deviceAddress(), physicalDevice, VkDeviceCreateInfo.calloc(stack))
			val queuePtr = stack.callocPointer(1)
			VK10.vkGetDeviceQueue(device, family, index, queuePtr)
			val handle = queuePtr.get(0)
			return if (handle == 0L) null else handle
		}
	}

	private fun probeGraphicsQueueFamily(): Int =
		HeadlessVulkanFixture().use { it.graphicsQueueFamilyIndex() }
}
