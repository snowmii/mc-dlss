package me.snowmii.dlss.bridge

import com.mojang.blaze3d.vulkan.VulkanDevice
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice
import org.lwjgl.vulkan.VK12
import org.lwjgl.vulkan.VkCommandBuffer

/**
 * Passive holder for Minecraft 26.2's live Vulkan instance / device / graphics queue,
 * plus a source of recording command buffers drawn from Minecraft's shared
 * [com.mojang.blaze3d.vulkan.VulkanCommandEncoder].
 *
 * Captured once, at [VulkanDevice] construction, by [me.snowmii.mixin.VulkanDeviceContextMixin]
 * and kept reachable through [VulkanContextRegistry].
 *
 * This object owns no queue submission of its own. [recordCommandBuffer] produces a fresh,
 * non-zero recording command buffer through the injected shared-encoder source, and
 * [submitCommandBuffer] hands it back to that same encoder, which carries it out with the
 * frame Minecraft was already going to submit. Nothing here waits on a fence or idles the
 * device: the encoder's own timeline is what orders this work.
 */
class VulkanContext private constructor(
	val instanceHandle: Long,
	val physicalDeviceHandle: Long,
	val deviceHandle: Long,
	val graphicsQueueHandle: Long,
	/** Queue family the graphics queue lives in, for Streamline's manual-hook Vulkan info. */
	val graphicsQueueFamily: Int,
	/** Index at which Streamline's own queues start: the number of queues the host created in [graphicsQueueFamily]. */
	val graphicsQueueIndex: Int,
	/** Queue family Minecraft's compute queue lives in, for Streamline's manual-hook Vulkan info. */
	val computeQueueFamily: Int,
	/** Index at which Streamline's own compute queues start: the number of queues the host created in [computeQueueFamily]. */
	val computeQueueIndex: Int,
	val commandBufferSource: () -> VkCommandBuffer,
	private val commandBufferSink: (VkCommandBuffer) -> Unit,
) {
	init {
		require(instanceHandle != 0L) { "Vulkan instance handle must be non-zero" }
		require(physicalDeviceHandle != 0L) { "Vulkan physical device handle must be non-zero" }
		require(deviceHandle != 0L) { "Vulkan device handle must be non-zero" }
		require(graphicsQueueHandle != 0L) { "Vulkan graphics queue handle must be non-zero" }
		requireNotNull(commandBufferSource) { "command buffer source must be provided" }
	}

	/** Records a fresh command buffer from the injected source, returning its non-zero handle wrapper. */
	fun recordCommandBuffer(): VkCommandBuffer = commandBufferSource()

	/**
	 * Ends [commandBuffer] and enqueues it on the frame Minecraft is already assembling.
	 *
	 * The buffer runs after everything the encoder recorded before this call, which is what puts
	 * DLSS work behind the world render it consumes without a submission of its own.
	 */
	fun submitCommandBuffer(commandBuffer: VkCommandBuffer) = commandBufferSink(commandBuffer)

	companion object {
		/**
		 * Production seam: capture Minecraft's live Vulkan context from a constructed
		 * [VulkanDevice] (called at ctor TAIL, all fields final). Returns null if any
		 * handle is zero, so the mod degrades gracefully.
		 *
		 * [VulkanDevice] exposes no physical-device accessor, so the mixin passes the
		 * constructor argument straight through. NGX initialization needs it.
		 */
		@JvmStatic
		fun fromVulkanDevice(device: VulkanDevice, physicalDevice: VulkanPhysicalDevice): VulkanContext? {
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
			return VulkanContext(
				instanceHandle,
				physicalDeviceHandle,
				deviceHandle,
				queueHandle,
				graphicsQueueFamily = graphicsPair.leftInt(),
				// Streamline's graphicsQueueIndex is where its own queues start, which is after the
				// queues Minecraft created in the family - the host queue COUNT from the create-info
				// map, not the pair's queue index.
				graphicsQueueIndex = queueFamilyMap.get(graphicsPair.leftInt()),
				// Same semantics for compute: the count of host queues in the compute family, and
				// 0/0 when Minecraft found no compute family (SL then cannot be told one).
				computeQueueFamily = computePair?.leftInt() ?: 0,
				computeQueueIndex = if (computePair != null) queueFamilyMap.get(computePair.leftInt()) else 0,
				commandBufferSource = { encoder.allocateAndBeginTransientCommandBuffer() },
				// execute() ends whatever the encoder was recording and appends this buffer behind
				// it, so the buffer has to be closed here first. It is not submitted by this call;
				// the encoder's next submit carries it with the rest of the frame.
				commandBufferSink = { commandBuffer ->
					VK12.vkEndCommandBuffer(commandBuffer)
					encoder.execute(commandBuffer)
				},
			)
		}

		/**
		 * Test / bootstrap seam: build a context from raw native handles plus an explicit
		 * command-buffer source. Used by [VulkanContextAccessTest] against a self-built
		 * headless Vulkan context, and usable by a future headless/native bootstrap.
		 */
		fun fromNativeHandles(
			instance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			vkQueue: Long,
			graphicsQueueFamily: Int = 0,
			graphicsQueueIndex: Int = 0,
			computeQueueFamily: Int = 0,
			computeQueueIndex: Int = 0,
			commandBufferSource: () -> VkCommandBuffer,
			commandBufferSink: (VkCommandBuffer) -> Unit = {},
		): VulkanContext = VulkanContext(
			instance,
			vkPhysicalDevice,
			vkDevice,
			vkQueue,
			graphicsQueueFamily,
			graphicsQueueIndex,
			computeQueueFamily,
			computeQueueIndex,
			commandBufferSource,
			commandBufferSink,
		)
	}
}
