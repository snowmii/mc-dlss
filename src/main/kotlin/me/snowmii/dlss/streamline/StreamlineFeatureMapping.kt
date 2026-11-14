package me.snowmii.dlss.streamline

import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct
import me.snowmii.streamline.SlVulkanFeatures

object StreamlineFeatureMapping {
	private val ALREADY_ENABLED: Set<String> =
		setOf("timelineSemaphore", "hostQueryReset", "synchronization2", "dynamicRendering")

	private val REQUIREMENTS_BY_NAME: Map<String, SlVulkanFeatures.FeatureRequirement> =
		SlVulkanFeatures.requirements().associateBy { it.name() }

	@JvmStatic
	fun requiredFeatures(features12: List<String>, features13: List<String>): List<VulkanFeature> {
		val required = ArrayList<VulkanFeature>()
		val seen = LinkedHashSet<String>()
		for (name in features12) {
			add(required, seen, name)
		}
		for (name in features13) {
			add(required, seen, name)
		}
		return required
	}

	private fun add(required: MutableList<VulkanFeature>, seen: MutableSet<String>, name: String) {
		if (!seen.add(name)) {
			return
		}
		if (name in ALREADY_ENABLED) {
			return
		}
		val requirement = REQUIREMENTS_BY_NAME[name] ?: return
		val structClass = when (requirement.sType()) {
			org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES ->
				org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features::class.java
			org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES ->
				org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features::class.java
			else -> return
		}
		required.add(
			VulkanFeature(VulkanPNextStruct(structClass), name, requirement.offset()),
		)
	}
}
