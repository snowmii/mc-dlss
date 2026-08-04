package me.snowmii.dlss

import com.mojang.blaze3d.vulkan.VulkanDevice
import org.lwjgl.vulkan.VkCommandBuffer

/**
 * Passive holder for Minecraft 26.2's live Vulkan instance / device / graphics queue,
 * plus a source of recording command buffers drawn from Minecraft's shared
 * [com.mojang.blaze3d.vulkan.VulkanCommandEncoder].
 *
 * Captured once, at [VulkanDevice] construction, by [me.snowmii.mixin.VulkanDeviceContextMixin]
 * and kept reachable through [VulkanContextRegistry].
 *
 * This object never submits GPU work itself. [recordCommandBuffer] only produces a fresh,
 * non-zero recording command buffer through the injected shared-encoder source; it is meant
 * to be called by later renderer slices inside the frame (M-8+, out of this slice's scope).
 */
class DlssVulkanContext private constructor(
	val instanceHandle: Long,
	val deviceHandle: Long,
	val graphicsQueueHandle: Long,
	val commandBufferSource: () -> VkCommandBuffer,
) {
	init {
		require(instanceHandle != 0L) { "Vulkan instance handle must be non-zero" }
		require(deviceHandle != 0L) { "Vulkan device handle must be non-zero" }
		require(graphicsQueueHandle != 0L) { "Vulkan graphics queue handle must be non-zero" }
		requireNotNull(commandBufferSource) { "command buffer source must be provided" }
	}

	/** Records a fresh command buffer from the injected source, returning its non-zero handle wrapper. */
	fun recordCommandBuffer(): VkCommandBuffer = commandBufferSource()

	companion object {
		/**
		 * Production seam: capture Minecraft's live Vulkan context from a constructed
		 * [VulkanDevice] (called at ctor TAIL, all fields final). Returns null if any
		 * handle is zero, so the mod degrades gracefully.
		 */
		@JvmStatic
		fun fromVulkanDevice(device: VulkanDevice): DlssVulkanContext? {
			val instanceHandle = device.instance().vkInstance().address()
			val deviceHandle = device.vkDevice().address()
			val queueHandle = device.graphicsQueue().vkQueue().address()
			if (instanceHandle == 0L || deviceHandle == 0L || queueHandle == 0L) {
				return null
			}
			val encoder = device.createCommandEncoder()
			return DlssVulkanContext(instanceHandle, deviceHandle, queueHandle) {
				encoder.allocateAndBeginTransientCommandBuffer()
			}
		}

		/**
		 * Test / bootstrap seam: build a context from raw native handles plus an explicit
		 * command-buffer source. Used by [VulkanContextAccessTest] against a self-built
		 * headless Vulkan context, and usable by a future headless/native bootstrap.
		 */
		@JvmStatic
		fun fromNativeHandles(
			instance: Long,
			vkDevice: Long,
			vkQueue: Long,
			commandBufferSource: () -> VkCommandBuffer,
		): DlssVulkanContext = DlssVulkanContext(instance, vkDevice, vkQueue, commandBufferSource)
	}
}
