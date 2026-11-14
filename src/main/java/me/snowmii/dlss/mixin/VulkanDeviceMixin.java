package me.snowmii.dlss.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.checkpoints.CheckpointExtension;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import me.snowmii.dlss.vk.VulkanContextCapture;
import me.snowmii.dlss.vk.VulkanContext;
import me.snowmii.dlss.vk.VulkanContextListeners;
import me.snowmii.dlss.vk.VulkanContextRegistry;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanDevice.class)
public class VulkanDeviceMixin {
	@Inject(at = @At("TAIL"), method = "<init>")
	private void dlssCaptureVulkanContext(
		VulkanInstance instance,
		VulkanPhysicalDevice physicalDevice,
		FeatureSet enabledFeatures,
		VkDevice vkDevice,
		long vma,
		CheckpointExtension checkpointExtension,
		CallbackInfo info
	) {
		VulkanContext context = VulkanContextCapture.capture((VulkanDevice) (Object) this, physicalDevice);
		if (context != null) {
			VulkanContextRegistry.register(context);
			VulkanContextListeners.dispatchRegistered(context);
		}
	}
}
