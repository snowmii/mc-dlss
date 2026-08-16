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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Raises the Vulkan swapchain's minimum image count to cover the declared DLSS-G back buffers
 * while FG is active.
 *
 * Minecraft creates the swapchain in {@code VulkanGpuSurface.configure} with
 * {@code minImageCount = max(3, caps.minImageCount())}, which is fine for the mod's declared
 * three back buffers but would starve DLSS-G if that declaration ever grew. This is the
 * pre-authorized structural exception of issue 13: while FG is active the count is raised to
 * at least the declared back buffers, and never lowered below what Minecraft would create.
 * Outside FG the value is exactly vanilla.
 *
 * The count is modified in place rather than the create call replaced, so the whole swapchain
 * creation path stays Minecraft's own. {@code ModifyExpressionValue} chains with any other mod
 * modifying the same read.
 *
 * Nothing here creates the DLSS path: the policy is read through the active controls, so a
 * session that never built the runtime answers with the vanilla count exactly as before.
 */
@Mixin(VulkanGpuSurface.class)
public class VulkanGpuSurfaceSwapchainMixin {
	private static final Logger MC_DLSS_LOGGER = LoggerFactory.getLogger(McDlss.MOD_ID);

	/**
	 * DIAGNOSTIC: names the present mode and the declared back buffers the recreated swapchain is
	 * about to be created under.
	 *
	 * DLSS-G's pacer wants a present mode that shows every present it schedules. The non-vsync
	 * preference list Minecraft picks from is {@code IMMEDIATE, MAILBOX, FIFO}, and MAILBOX keeps
	 * only the newest queued image per refresh - it would discard exactly the frames the pacer
	 * spaced out, one per app frame at 2x and three at 4x. Which one a driver actually resolves
	 * to cannot be read from this side; the line reports it once per reconfigure, which is rare.
	 */
	@Inject(method = "configure(Lcom/mojang/blaze3d/systems/GpuSurface$Configuration;)V", at = @At("HEAD"))
	private void mcDlssReportConfiguration(final GpuSurface.Configuration configuration, final CallbackInfo ci) {
		final RuntimeControls controls = ClientRuntime.active().activeControls();
		MC_DLSS_LOGGER.info(
			"DLSS surface configure: {}x{} present={} fg={} declaredBackBuffers={}",
			configuration.width(),
			configuration.height(),
			configuration.presentMode(),
			controls != null && controls.getSurfacePolicy().getArmed(),
			controls == null ? 0 : controls.getSurfacePolicy().getDeclaredBackBuffers()
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
