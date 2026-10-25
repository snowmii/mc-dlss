package me.snowmii.dlss.vk

import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature

/** Streamline's feature bits, configured before Minecraft creates its Vulkan device. */
object VulkanFeatureContributions {
    private var features: Set<VulkanFeature> = emptySet()

    @JvmStatic
    @Synchronized
    fun register(contribution: Collection<VulkanFeature>) {
        features = contribution.toSet()
    }

    @JvmStatic
    @Synchronized
    fun all(): Set<VulkanFeature> = features
}
