package me.snowmii.dlss.streamline

import me.snowmii.streamline.Streamline
import me.snowmii.vklib.VulkanFeatureContributions
import me.snowmii.vklib.api.DeviceRequirements
import me.snowmii.vklib.api.DeviceRequirementsRegistry
import me.snowmii.vklib.api.VulkanContextListeners

/** Registers Streamline device requirements and Vulkan proxy activation with vk-lib. */
object VkLibBootstrap {
	@JvmStatic
	fun install() {
		VulkanContextListeners.onRegistered { context ->
			Streamline.activateVulkanProxies(context)
		}

		val queues = Streamline.queryQueueRequirements()
		DeviceRequirementsRegistry.register(
			DeviceRequirements.builder("streamline")
				.extraGraphicsQueues(queues.graphicsQueues())
				.extraComputeQueues(queues.computeQueues())
				.build(),
		)
		DeviceRequirementsRegistry.registerExtensionContributor("streamline") { extensions, vkInstance, vkPhysicalDevice ->
			Streamline.addDeviceExtensions(extensions, vkInstance, vkPhysicalDevice)
		}
		VulkanFeatureContributions.register(
			"streamline",
			StreamlineFeatureMapping.requiredFeatures(
				Streamline.queryDeviceFeatures12(),
				Streamline.queryDeviceFeatures13(),
			),
		)
	}
}
