package me.snowmii.dlss.mixin;

import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.render.WorldPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Measures how long {@code VulkanGpuSurface.acquireNextTexture} takes, for the pacing probe.
 *
 * Streamline's DLSS-G intercepts {@code vkAcquireNextImageKHR} along with the queue present and
 * runs both asynchronously, holding generated frames back for even spacing. When the swapchain
 * has fewer images than the multiplier needs, the app does not fail - it blocks here, at the top
 * of the next frame, and the interval DLSS-G then divides is the blocked one. That is invisible
 * in a frame-rate number and unmistakable in this span.
 *
 * Purely observational: no marker, no native call, no behaviour. The mixin exists because this
 * call is Minecraft's own and there is no other seam around it.
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
