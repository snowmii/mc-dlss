package me.snowmii.dlss.mixin

import me.snowmii.dlss.vk.DeviceExtensionRules
import me.snowmii.dlss.vk.DeviceQueueRules
import me.snowmii.streamline.SlQueueRequirements
import it.unimi.dsi.fastutil.ints.Int2IntArrayMap
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VulkanBackendExtensionMixinTest {
    @Test
    fun reservesDisjointStreamlineQueuesInSharedAndSeparateFamilies() {
        val host = Int2IntArrayMap().apply { put(2, 1); put(4, 2) }
        val requirements = SlQueueRequirements(1, 2, 0)
        val shared = DeviceQueueRules.reserve(host, 2, 2, requirements)
        assertEquals(1, shared.graphicsStart())
        assertEquals(2, shared.computeStart())
        assertEquals(4, shared.counts().get(2))
        assertEquals(1, host.get(2), "the host's queue map must remain unchanged")

        val separate = DeviceQueueRules.reserve(host, 2, 4, requirements)
        assertEquals(1, separate.graphicsStart())
        assertEquals(2, separate.computeStart())
        assertEquals(2, separate.counts().get(2))
        assertEquals(4, separate.counts().get(4))
    }

    @Test
    fun preservesBothModsAndVanillaWhenNormalizingTheComposite() {
        val feature = VulkanFeature(VulkanPNextStruct(VkPhysicalDeviceVulkan12Features::class.java), "bufferDeviceAddress")
        val vanilla = FeatureSet("vanilla", setOf("VK_EXT_buffer_device_address"), emptySet(), FeatureSet.Condition { false })
        val lumina = FeatureSet("lumina", setOf("VK_KHR_ray_query"), setOf(feature))
        val dlss = FeatureSet("mc-dlss", setOf("VK_KHR_buffer_device_address", "VK_NV_optical_flow"), emptySet())
        val composite = vanilla.composite(lumina).composite(dlss)
        val result = DeviceExtensionRules.normalize(composite)

        assertContains(result.extensions(), "VK_KHR_ray_query")
        assertContains(result.extensions(), "VK_KHR_buffer_device_address")
        assertContains(result.extensions(), "VK_KHR_format_feature_flags2")
        assertFalse("VK_EXT_buffer_device_address" in result.extensions())
        assertEquals(setOf(feature), result.features())
        assertTrue(result.contains(lumina))
        assertTrue(result.contains(dlss))
        assertEquals(composite.condition(), result.condition())
    }

    @Test
    fun normalizesDeviceExtensionDependenciesAndAliases() {
        val extensions = mutableSetOf(
            "VK_NV_optical_flow",
            "VK_KHR_buffer_device_address",
            "VK_EXT_buffer_device_address",
        )

        DeviceExtensionRules.normalize(extensions)

        assertContains(extensions, "VK_KHR_format_feature_flags2")
        assertContains(extensions, "VK_KHR_buffer_device_address")
        assertFalse("VK_EXT_buffer_device_address" in extensions)
    }
}
