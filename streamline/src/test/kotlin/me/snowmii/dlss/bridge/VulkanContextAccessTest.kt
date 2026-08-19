package me.snowmii.dlss.bridge
import me.snowmii.streamline.VulkanContext
import me.snowmii.streamline.VulkanContextRegistry

import me.snowmii.dlss.NativeBridge
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Proves [VulkanContext] captures a real, non-zero Vulkan instance,
 * device, and graphics-queue from live handles and produces a non-zero recording
 * [org.lwjgl.vulkan.VkCommandBuffer] through its injected command-buffer source.
 *
 * The test drives the exact production seams ([VulkanContext.fromNativeHandles],
 * [VulkanContext.allocateRecordingCommandBuffer], [VulkanContextRegistry]) against a headless
 * Vulkan context it builds itself - no Minecraft instance, device, or window needed.
 */
@NativeBridge
class VulkanContextAccessTest {

	@Test
	fun capturesNonZeroHandlesAndProducesRecordingCommandBuffer() {
		HeadlessVulkanFixture().use { fixture ->
			val context = VulkanContext.fromNativeHandles(
				fixture.instanceAddress(),
				fixture.physicalDeviceAddress(),
				fixture.deviceAddress(),
				fixture.queueAddress(),
				0,
				0,
				0,
				0,
				{ fixture.allocateAndBeginCommandBuffer() },
				{ },
			)

			assertTrue(context.instanceHandle != 0L, "instance handle must be non-zero")
			assertTrue(context.physicalDeviceHandle != 0L, "physical device handle must be non-zero")
			assertTrue(context.deviceHandle != 0L, "device handle must be non-zero")
			assertTrue(context.graphicsQueueHandle != 0L, "graphics queue handle must be non-zero")
			assertEquals(fixture.instanceAddress(), context.instanceHandle)
			assertEquals(fixture.physicalDeviceAddress(), context.physicalDeviceHandle)
			assertEquals(fixture.deviceAddress(), context.deviceHandle)
			assertEquals(fixture.queueAddress(), context.graphicsQueueHandle)

			val first = context.allocateRecordingCommandBuffer()
			assertTrue(first.address() != 0L, "recorded command buffer must be non-zero")

			val second = context.allocateRecordingCommandBuffer()
			assertTrue(second.address() != 0L, "second recorded command buffer must be non-zero")
			assertNotEquals(first.address(), second.address(), "each recording must be a fresh command buffer")

			VulkanContextRegistry.register(context)
			assertSame(context, VulkanContextRegistry.getCurrent(), "the registry hands back the registered context")
		}
	}
}
