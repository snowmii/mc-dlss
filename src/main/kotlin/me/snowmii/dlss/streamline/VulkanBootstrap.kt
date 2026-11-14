package me.snowmii.dlss.streamline

import me.snowmii.dlss.vk.VulkanContextListeners
import me.snowmii.dlss.vk.VulkanFeatureContributions
import me.snowmii.streamline.SlQueueRequirements
import me.snowmii.streamline.Streamline

object VulkanBootstrap {
    @JvmStatic
    var queueRequirements: SlQueueRequirements? = null
        private set

    @JvmStatic
    fun install() {
        VulkanContextListeners.onRegistered { context ->
            Streamline.activateVulkanProxies(context)
        }
        queueRequirements = Streamline.queryQueueRequirements()
        VulkanFeatureContributions.register(
            StreamlineFeatureMapping.requiredFeatures(
                Streamline.queryDeviceFeatures12(),
                Streamline.queryDeviceFeatures13(),
            ),
        )
    }
}
