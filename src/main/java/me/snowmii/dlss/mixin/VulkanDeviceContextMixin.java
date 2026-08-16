package me.snowmii.dlss.mixin;

import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanInstance;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.checkpoints.CheckpointExtension;
import me.snowmii.streamline.ExtensionBootstrap;
import me.snowmii.streamline.VulkanContext;
import me.snowmii.dlss.bridge.VulkanContextRegistry;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Captures Minecraft 26.2's live Vulkan instance, physical device, device, and graphics
 * queue the moment the VulkanDevice is constructed. Runs at ctor TAIL when all fields are
 * final, so the instance / device / graphics queue / shared command encoder are all reachable.
 *
 * VulkanDevice keeps no physical-device field and exposes no accessor for it, so the
 * physical device is taken from the constructor argument list. NGX initialization requires it.
 *
 * Only STORES the command-buffer source (never invokes it): invoking Minecraft's shared
 * encoder outside a frame could disturb its command-buffer/submission state. Recording
 * happens later, from the render loop, via VulkanContext.recordCommandBuffer().
 */
@Mixin(VulkanDevice.class)
public class VulkanDeviceContextMixin {
	@Inject(at = @At("TAIL"), method = "<init>")
	private void mcDlssCaptureVulkanContext(
		ShaderSource defaultShaderSource,
		VulkanInstance instance,
		VulkanPhysicalDevice physicalDevice,
		Set<String> enabledDeviceExtensions,
		VkDevice vkDevice,
		long vma,
		CheckpointExtension checkpointExtension,
		CallbackInfo info
	) {
		VulkanContext context =
			VulkanContext.fromVulkanDevice((VulkanDevice) (Object) this, physicalDevice);
		if (context != null) {
			VulkanContextRegistry.register(context);
			// Loud on failure: a device Streamline cannot hook must not be silently presented as
			// proxy-active. bootstrap throws NativeException at this same seam, so the injection
			// point is already the one that fails device creation rather than the frame loop.
			ExtensionBootstrap.activateVulkanProxies(context);
		}
	}
}
