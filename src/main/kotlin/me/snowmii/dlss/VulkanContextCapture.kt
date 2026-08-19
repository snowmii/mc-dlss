package me.snowmii.dlss

import com.mojang.blaze3d.vulkan.VulkanDevice
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice
import me.snowmii.streamline.VulkanContext
import org.lwjgl.vulkan.VK12

/**
 * Builds the SDK's [VulkanContext] from live Minecraft [VulkanDevice] /
 * [VulkanPhysicalDevice]. Invoked from ctor-TAIL in `VulkanDeviceContextMixin`. Lives
 * outside `me.snowmii.dlss.mixin` because Mixin forbids referencing classes in a mixin package.
 *
 * [VulkanDevice] exposes no physical-device accessor; the mixin passes the constructor
 * argument through. Streamline initialization needs it.
 */
object VulkanContextCapture {
	/**
	 * Production seam: capture Minecraft's live Vulkan context from a constructed [VulkanDevice]
	 * (called at ctor TAIL, all fields final). Returns null if any handle is zero, so the mod
	 * degrades gracefully.
	 */
	@JvmStatic
	fun capture(device: VulkanDevice, physicalDevice: VulkanPhysicalDevice): VulkanContext? {
		val instanceHandle = device.instance().vkInstance().address()
		val physicalDeviceHandle = physicalDevice.vkPhysicalDevice().address()
		val deviceHandle = device.vkDevice().address()
		val queueHandle = device.graphicsQueue().vkQueue().address()
		// Minecraft records no graphics queue when no family can present, which is exactly the
		// case that must not reach Streamline's Vulkan info - degrade like the zero handles do.
		val graphicsPair = physicalDevice.graphicsQueueFamilyAndIndex() ?: return null
		if (instanceHandle == 0L || physicalDeviceHandle == 0L || deviceHandle == 0L || queueHandle == 0L) {
			return null
		}
		val encoder = device.createCommandEncoder()
		val queueFamilyMap = physicalDevice.queueFamilyCreateInfoMap()
		val computePair = physicalDevice.computeQueueFamilyAndIndex()
		return VulkanContext.fromNativeHandles(
			instanceHandle,
			physicalDeviceHandle,
			deviceHandle,
			queueHandle,
			graphicsPair.leftInt(),
			// Streamline's graphicsQueueIndex is where its own queues start, which is after the
			// queues Minecraft created in the family - the host queue COUNT from the create-info
			// map, not the pair's queue index.
			queueFamilyMap.get(graphicsPair.leftInt()),
			// Same semantics for compute: the count of host queues in the compute family, and
			// 0/0 when Minecraft found no compute family (SL then cannot be told one).
			computePair?.leftInt() ?: 0,
			computePair?.let { queueFamilyMap.get(it.leftInt()) } ?: 0,
			{ encoder.allocateAndBeginTransientCommandBuffer() },
			// execute() ends whatever the encoder was recording and appends this buffer behind it,
			// so the buffer has to be closed here first. It is not submitted by this call; the
			// encoder's next submit carries it with the rest of the frame.
			{ commandBuffer ->
				VK12.vkEndCommandBuffer(commandBuffer)
				encoder.execute(commandBuffer)
			}
		)
	}
}
