package me.snowmii.dlss.bridge

import com.mojang.blaze3d.vulkan.init.VulkanFeature
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct
import me.snowmii.streamline.SlVulkanFeatures

/**
 * Maps Streamline's Vulkan feature names — as the bridge reports them through
 * [me.snowmii.streamline.ExtensionBootstrap.queryDeviceFeatures12]/[queryDeviceFeatures13] —
 * onto Minecraft's [VulkanFeature] records, so the names can merge into the enabled-feature
 * set Minecraft passes to `vkCreateDevice`.
 *
 * The name→(sType, structSize, offset) table lives in the SDK as plain data
 * ([SlVulkanFeatures.requirements]); this object adds the Blaze3D mapping and the engine
 * knowledge of what Minecraft 26.2 already enables.
 */
object StreamlineFeatureMapping {
	/**
	 * Feature names Minecraft 26.2 already enables in its own `REQUIRED_DEVICE_FEATURES`.
	 * Re-adding them would either duplicate an identical [VulkanFeature] record or enable the
	 * same feature through a second struct (synchronization2, dynamicRendering); skipping
	 * keeps the merged set from growing past what the mod adds.
	 */
	private val ALREADY_ENABLED: Set<String> =
		setOf("timelineSemaphore", "hostQueryReset", "synchronization2", "dynamicRendering")

	private val REQUIREMENTS_BY_NAME: Map<String, SlVulkanFeatures.FeatureRequirement> =
		SlVulkanFeatures.requirements().associateBy { it.name() }

	/**
	 * The [VulkanFeature] records for the feature names Streamline requires, in `features12`
	 * then `features13` order, skipping names Minecraft already enables and deduping by name
	 * across both lists.
	 */
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
		// Dedupe BEFORE the ALREADY_ENABLED check, exactly as the pre-split mapping did: a name
		// already seen (first-wins across the two lists) returns without touching required.
		if (!seen.add(name)) {
			return
		}
		if (name in ALREADY_ENABLED) {
			return
		}
		val requirement = REQUIREMENTS_BY_NAME[name] ?: return
		required.add(
			VulkanFeature(VulkanPNextStruct(requirement.sType(), requirement.structSize()), name, requirement.offset()),
		)
	}
}
