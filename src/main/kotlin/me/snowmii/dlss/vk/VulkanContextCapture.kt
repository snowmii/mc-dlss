package me.snowmii.dlss.vk

import com.mojang.renderpearl.backend.vulkan.VulkanDevice
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice
import org.lwjgl.vulkan.VK12
import me.snowmii.dlss.streamline.VulkanBootstrap

object VulkanContextCapture {
	@JvmStatic
	fun capture(device: VulkanDevice, physicalDevice: VulkanPhysicalDevice): VulkanContext? {
		val instanceHandle = device.instance().vkInstance().address()
		val physicalDeviceHandle = physicalDevice.vkPhysicalDevice().address()
		val deviceHandle = device.vkDevice().address()
		val queueHandle = device.graphicsQueue().vkQueue().address()
		val graphicsPair = physicalDevice.graphicsQueueFamilyAndIndex() ?: return null
		if (instanceHandle == 0L || physicalDeviceHandle == 0L || deviceHandle == 0L || queueHandle == 0L) {
			return null
		}
		val encoder = device.createCommandEncoder()
		val queueFamilyMap = physicalDevice.queueFamilyCreateInfoMap()
		val computePair = physicalDevice.computeQueueFamilyAndIndex()
		val queues = DeviceQueueRules.reserve(queueFamilyMap, graphicsPair.leftInt(),
			computePair?.leftInt() ?: -1, VulkanBootstrap.queueRequirements)
		return VulkanContext.fromCapturedDevice(
			instanceHandle,
			physicalDevice.vkPhysicalDevice(),
			device.vkDevice(),
			queueHandle,
			graphicsPair.leftInt(),
			queues.graphicsStart(),
			computePair?.leftInt() ?: 0,
			queues.computeStart(),
			{ encoder.allocateAndBeginTransientCommandBuffer() },
			{ commandBuffer ->
				val result = VK12.vkEndCommandBuffer(commandBuffer)
				check(result == VK12.VK_SUCCESS) { "vkEndCommandBuffer failed with VkResult $result" }
				encoder.execute(commandBuffer)
			},
			{ destroy -> encoder.queueForDestroy { destroy.run() } },
		)
	}
}
