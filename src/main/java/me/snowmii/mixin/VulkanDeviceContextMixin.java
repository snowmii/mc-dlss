package me.snowmii.mixin;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import me.snowmii.dlss.DlssVulkanContext;
import me.snowmii.dlss.VulkanContextRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures Minecraft 26.2's live Vulkan instance, device, and graphics queue the moment
 * the VulkanDevice is constructed. Runs at ctor TAIL when all fields are final, so the
 * instance / device / graphics queue / shared command encoder are all reachable.
 *
 * Only STORES the command-buffer source (never invokes it): invoking Minecraft's shared
 * encoder outside a frame could disturb its command-buffer/submission state. Recording
 * happens later, from the render loop, via DlssVulkanContext.recordCommandBuffer().
 */
@Mixin(VulkanDevice.class)
public class VulkanDeviceContextMixin {
	@Inject(at = @At("TAIL"), method = "<init>")
	private void mcDlssCaptureVulkanContext(CallbackInfo info) {
		DlssVulkanContext context = DlssVulkanContext.fromVulkanDevice((VulkanDevice) (Object) this);
		if (context != null) {
			VulkanContextRegistry.register(context);
		}
	}
}
