package me.snowmii.dlss.api

import com.mojang.renderpearl.api.textures.GpuTextureView
import me.snowmii.vklib.api.ImageBinding
import me.snowmii.vklib.imageBindingOf

/** Builds RR guides from render targets. Null if any view is non-Vulkan. */
fun rayReconstructionGuidesOf(
	noisyColor: GpuTextureView,
	diffuseAlbedo: GpuTextureView,
	specularAlbedo: GpuTextureView,
	normalRoughness: GpuTextureView,
	specularHitDistance: GpuTextureView,
): RayReconstructionGuides? {
	val noisy = imageBindingOf(noisyColor) ?: return null
	val diffuse = imageBindingOf(diffuseAlbedo) ?: return null
	val specular = imageBindingOf(specularAlbedo) ?: return null
	val normal = imageBindingOf(normalRoughness) ?: return null
	val hit = imageBindingOf(specularHitDistance) ?: return null
	return RayReconstructionGuides(noisy, diffuse, specular, normal, hit)
}
