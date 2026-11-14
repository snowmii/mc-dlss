package me.snowmii.dlss.api

import com.mojang.renderpearl.api.textures.GpuTextureView
import com.mojang.renderpearl.backend.vulkan.VulkanConst
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView

/** Null for a non-Vulkan view (OpenGL backend / tests). */
fun imageBindingOf(view: GpuTextureView?): ImageBinding? {
	val vulkan = view as? VulkanGpuTextureView ?: return null
	return ImageBinding(
		vulkan.vkImageView(),
		vulkan.texture().vkImage(),
		VulkanConst.toVk(vulkan.texture().format),
	)
}

/** Builds RR guides from render targets. Null if any view is non-Vulkan. */
fun rayReconstructionGuidesOf(
	noisyColor: GpuTextureView,
	diffuseAlbedo: GpuTextureView,
	specularAlbedo: GpuTextureView,
	normalRoughness: GpuTextureView,
	specularHitDistance: GpuTextureView? = null,
	resetHistory: Boolean = false,
	depth: GpuTextureView? = null,
	specularMotion: GpuTextureView? = null,
	reflection: GpuTextureView? = null,
): RayReconstructionGuides? {
	val noisy = imageBindingOf(noisyColor) ?: return null
	val diffuse = imageBindingOf(diffuseAlbedo) ?: return null
	val specular = imageBindingOf(specularAlbedo) ?: return null
	val normal = imageBindingOf(normalRoughness) ?: return null
	val hit = specularHitDistance?.let(::imageBindingOf) ?: ImageBinding(0L, 0L, 0)
	val depthBinding = if (depth != null) imageBindingOf(depth) ?: return null else null
	val specularMotionBinding = if (specularMotion != null) imageBindingOf(specularMotion) ?: return null else null
	val reflectionBinding = if (reflection != null) imageBindingOf(reflection) ?: return null else null
	return RayReconstructionGuides(
		noisy, diffuse, specular, normal, hit, resetHistory, depthBinding, specularMotionBinding, reflectionBinding,
	)
}
