package me.snowmii.dlss.mixin;

import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanInstance;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.checkpoints.CheckpointExtension;
import me.snowmii.dlss.VulkanContextCapture;
import me.snowmii.streamline.Streamline;
import me.snowmii.streamline.VulkanContext;
import me.snowmii.streamline.VulkanContextRegistry;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Captures live Vulkan handles at {@code VulkanDevice} ctor TAIL (fields are then final).
 *
 * {@code VulkanDevice} has no physical-device field; take it from the constructor args.
 * Streamline initialization needs it.
 *
 * Store the command-buffer source; do not invoke it here. Calling Minecraft's shared encoder
 * outside a frame can disturb command-buffer/submission state. Recording happens later via
 * {@code VulkanContext.allocateRecordingCommandBuffer()}.
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
			VulkanContextCapture.capture((VulkanDevice) (Object) this, physicalDevice);
		if (context != null) {
			VulkanContextRegistry.register(context);
			// Loud on failure: a device Streamline cannot hook must not be silently presented as
			// proxy-active. bootstrap throws StreamlineException at this same seam, so the injection
			// point is already the one that fails device creation rather than the frame loop.
			Streamline.activateVulkanProxies(context);
		}
	}
}
