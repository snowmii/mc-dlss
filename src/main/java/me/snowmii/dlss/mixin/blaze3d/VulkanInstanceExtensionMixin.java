package me.snowmii.dlss.mixin.blaze3d;

import com.mojang.blaze3d.vulkan.VulkanInstance;
import me.snowmii.streamline.Streamline;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/** Adds Streamline instance extensions after GLFW's, before vkCreateInstance. */
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
		enabledExtensions.addAll(Streamline.queryInstanceExtensions());
	}
}
