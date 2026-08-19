package me.snowmii.dlss.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import me.snowmii.McDlss;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.client.RuntimeControls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Raises {@code minImageCount} to the declared DLSS-G back buffers while FG is on, never
 * below vanilla {@code max(3, caps.minImageCount())}. Outside FG the value is vanilla.
 *
 * Modified in place so swapchain creation stays Minecraft's. Policy is read through active
 * controls: no runtime → vanilla count.
 */
@Mixin(VulkanGpuSurface.class)
public class VulkanGpuSurfaceSwapchainMixin {
	@Unique
	private static final Logger MC_DLSS_LOGGER = LoggerFactory.getLogger(McDlss.MOD_ID);

	/**
	 * Present mode + declared back buffers at reconfigure. MAILBOX would drop frames the
	 * pacer spaced; driver resolution is not readable from here.
	 */
	@Inject(method = "configure(Lcom/mojang/blaze3d/systems/GpuSurface$Configuration;)V", at = @At("HEAD"))
	private void mcDlssReportConfiguration(final GpuSurface.Configuration config, final CallbackInfo ci) {
		final RuntimeControls controls = ClientRuntime.active().activeControls();
		MC_DLSS_LOGGER.info(
			"DLSS surface configure: {}x{} present={} fg={} declaredBackBuffers={}",
			config.width(),
			config.height(),
			config.presentMode(),
			controls != null && controls.getSurfacePolicy().getUserEnabled(),
			controls == null ? 0 : controls.getSurfacePolicy().getRequiredSwapchainImages()
		);
	}

	@ModifyExpressionValue(
		method = "configure(Lcom/mojang/blaze3d/systems/GpuSurface$Configuration;)V",
		at = @At(
			value = "INVOKE",
			target = "Ljava/lang/Math;max(II)I"
		)
	)
	private int mcDlssFgMinImageCount(final int original) {
		final RuntimeControls controls = ClientRuntime.active().activeControls();
		if (controls == null) {
			return original;
		}
		return controls.getSurfacePolicy().minImageCount(original);
	}
}
