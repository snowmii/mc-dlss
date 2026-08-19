package me.snowmii.dlss.mixin;

import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.render.WorldPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observational span around {@code acquireNextTexture}. FG can block here when it holds
 * swapchain images; that stall is invisible in FPS and visible in this span.
 */
@Mixin(VulkanGpuSurface.class)
public class VulkanGpuSurfaceAcquireMixin {
	@Inject(method = "acquireNextTexture()V", at = @At("HEAD"))
	private void mcDlssAcquireStart(final CallbackInfo ci) {
		final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		if (phase != null) phase.acquireStart();
	}

	@Inject(method = "acquireNextTexture()V", at = @At("RETURN"))
	private void mcDlssAcquireEnd(final CallbackInfo ci) {
		final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		if (phase != null) phase.acquireEnd();
	}
}
