package me.snowmii.dlss.sl

import java.nio.file.Path
import me.snowmii.dlss.NativeBridge
import me.snowmii.streamline.ExtensionBootstrap
import me.snowmii.dlss.bridge.HeadlessVulkanFixture
import me.snowmii.streamline.Native
import me.snowmii.streamline.NativeApi
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

/**
 * Verifies the device-requirements merge. the device-requirements merge. Streamline's Vulkan 1.2/1.3 feature names and the
 * summed DLSS_G queue counts surface through the bridge, and proxy activation succeeds against
 * a device that actually holds the merged queue layout.
 *
 * Methods are ORDERED because the fork is one process: the live-device test's close shuts the
 * Streamline runtime down (slShutdown, while the fixture device is still alive - the fix that
 * keeps the fork's JVM exit from crashing in sl.common.dll / nvcuda64.dll), and the
 * bootstrap-dependent queries need the runtime up, so they run before it and nothing
 * bootstraps after it.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@NativeBridge
class StreamlineRequirementsMergeTest {

	@Order(1)
	@Test
	fun `SL features12 and 13 surface through the bridge`() {
		// queryInstanceExtensions runs the same pre-Vulkan bootstrap Minecraft's instance seam
		// performs, so the feature queries run against a live Streamline runtime. The bridge
		// closes between the two seams and the native module is pinned for the JVM lifetime, so
		// the module's bootstrap state survives the close; the fresh bridge still bootstraps in
		// place because slInit is idempotent and every production seam (ExtensionBootstrap)
		// does the same.
		ExtensionBootstrap.queryInstanceExtensions()
		Native.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				bridge.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
			)
			val features12 = bridge.queryDeviceFeatures12()
			val features13 = bridge.queryDeviceFeatures13()
			// The observed workstation set from the live probe; if a different driver/runtime
			// reports a different set the delta is reported rather than silently asserted away.
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
		Native.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				bridge.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
			)
			val requirements = bridge.queryQueueRequirements()
			// Summed across the loaded features (DLSS, DLSS-G, Reflex); the observed workstation
			// totals come from the live probe.
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
		// The query bridge closes before the device exists, when its close path is a no-op;
		// the pinned module keeps the bootstrap state for the activation bridge below.
		val requirements = Native.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				bridge.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
			)
			bridge.queryQueueRequirements()
		}

		// The production merge starts from Minecraft's {graphicsFamily: 1} queue map and adds
		// SL's extra graphics and compute queues to the families Streamline would record at
		// activation. The first graphics family is compute-capable on this workstation, so
		// both merges land in the same family - exactly the layout the fixture must hold.
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
			// The fixture OUTLIVES the bridge: the close's slShutdown must run while the
			// device is still alive.
			Native.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
				)
				// The fixture creates one host queue in the family, so Streamline's own queues
				// start at index 1 - right after the host's, as slSetVulkanInfo records them.
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
					NativeApi.SUCCESS_RESULT,
					layout(),
					"activation must succeed against the merged queue layout",
				)
				// Idempotent: the same seven values must not re-call slSetVulkanInfo.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					layout(),
					"repeated activation must succeed",
				)

				// The device must actually hold the queues Streamline was told about: the host's
				// queue, then SL's graphics + compute queues after it, and nothing beyond.
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

				// Arm the close path: the already-activated tuple is recorded through the
				// existing initialize, so this bridge's close runs the orderly slShutdown
				// while the device is still alive instead of leaving the fork to crash at
				// exit. Runs last (Order 4) because nothing in this fork may bootstrap
				// after the shutdown.
				SrLiveSession.recordActivatedSession(bridge, fixture, dataPath)
			}
		}
	}

	/**
	 * The queue handle at [index] in [family] of the fixture's device, or null when the index
	 * is out of range - which is exactly how the test proves the device holds the merged count
	 * and nothing beyond it.
	 */
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

	/**
	 * The family the fixture creates its queues in, discovered with a throwaway default fixture
	 * so the augmented fixture can be built with the extras keyed by the right family.
	 */
	private fun probeGraphicsQueueFamily(): Int =
		HeadlessVulkanFixture().use { it.graphicsQueueFamilyIndex() }
}
