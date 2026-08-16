package me.snowmii.dlss.bridge
import java.util.function.Consumer;
import java.util.function.Supplier;
import me.snowmii.streamline.VulkanContext;
import me.snowmii.streamline.VulkanContextRegistry;

import me.snowmii.dlss.NativeBridge
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M-7 rung: proves [VulkanContext] captures a real, non-zero Vulkan instance,
 * device, and graphics-queue from live handles and produces a non-zero recording
 * [org.lwjgl.vulkan.VkCommandBuffer] through its injected command-buffer source.
 *
 * The test drives the exact production seams ([VulkanContext.fromNativeHandles],
 * [VulkanContext.recordCommandBuffer], [VulkanContextRegistry]) against a headless
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
				Supplier { fixture.allocateAndBeginCommandBuffer() },
				Consumer { },
			)

			// Handles present and are the real headless context's handles.
			assertTrue(context.instanceHandle != 0L, "instance handle must be non-zero")
			assertTrue(context.physicalDeviceHandle != 0L, "physical device handle must be non-zero")
			assertTrue(context.deviceHandle != 0L, "device handle must be non-zero")
			assertTrue(context.graphicsQueueHandle != 0L, "graphics queue handle must be non-zero")
			assertEquals(fixture.instanceAddress(), context.instanceHandle)
			assertEquals(fixture.physicalDeviceAddress(), context.physicalDeviceHandle)
			assertEquals(fixture.deviceAddress(), context.deviceHandle)
			assertEquals(fixture.queueAddress(), context.graphicsQueueHandle)

			// The recording command buffer is a real, non-zero VkCommandBuffer.
			val first = context.recordCommandBuffer()
			assertTrue(first.address() != 0L, "recorded command buffer must be non-zero")

			// A second recording also succeeds and yields a distinct buffer.
			val second = context.recordCommandBuffer()
			assertTrue(second.address() != 0L, "second recorded command buffer must be non-zero")
			assertNotEquals(first.address(), second.address(), "each recording must be a fresh command buffer")

			// Mod-level registration keeps the captured context reachable.
			VulkanContextRegistry.register(context)
			assertSame(context, VulkanContextRegistry.getCurrent(), "the registry hands back the registered context")
		}
	}
}
