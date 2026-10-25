package me.snowmii.dlss.api

import org.lwjgl.vulkan.VkCommandBuffer

/** Optional render-thread scope recorder. Scopes must only record into the supplied buffer. */
object GpuProfiling {
    var begin: ((VkCommandBuffer, String) -> AutoCloseable)? = null
}
