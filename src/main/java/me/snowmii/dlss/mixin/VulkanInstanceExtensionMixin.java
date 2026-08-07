package me.snowmii.dlss.mixin;

import com.mojang.blaze3d.vulkan.VulkanInstance;
import me.snowmii.dlss.bridge.ExtensionBootstrap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/** Adds NGX requirements after GLFW extensions are collected and before vkCreateInstance. */
@Mixin(VulkanInstance.class)
public class VulkanInstanceExtensionMixin {
	@Shadow @Final private Set<String> enabledExtensions;

	@Inject(
		method = "<init>",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vulkan/VulkanDebug;create(IZLjava/util/Set;Ljava/util/Set;)Lcom/mojang/blaze3d/vulkan/VulkanDebug;",
			shift = At.Shift.BEFORE
		)
	)
	private void mcDlssAddInstanceExtensions(CallbackInfo info) {
		enabledExtensions.addAll(ExtensionBootstrap.queryInstanceExtensions());
	}
}
