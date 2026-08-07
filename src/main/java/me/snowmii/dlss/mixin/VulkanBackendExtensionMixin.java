package me.snowmii.dlss.mixin;

import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import me.snowmii.dlss.bridge.ExtensionBootstrap;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collection;
import java.util.Set;

/** Extends VulkanBackend's local device-extension set immediately before vkCreateDevice. */
@Mixin(VulkanBackend.class)
public abstract class VulkanBackendExtensionMixin {
	@Shadow
	private static VkDevice createDevice(
		Collection<String> extensions,
		VulkanPhysicalDevice physicalDevice,
		Set<VulkanFeature> features
	) throws BackendCreationException {
		throw new AssertionError();
	}

	@Redirect(
		method = "createDevice",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vulkan/VulkanBackend;createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;"
		)
	)
	private VkDevice mcDlssCreateDeviceWithExtensions(
		Collection<String> extensions,
		VulkanPhysicalDevice physicalDevice,
		Set<VulkanFeature> features
	) throws BackendCreationException {
		VkPhysicalDevice vkPhysicalDevice = physicalDevice.vkPhysicalDevice();
		ExtensionBootstrap.addDeviceExtensions(
			extensions,
			vkPhysicalDevice.getInstance().address(),
			vkPhysicalDevice.address()
		);
		return createDevice(extensions, physicalDevice, features);
	}
}
