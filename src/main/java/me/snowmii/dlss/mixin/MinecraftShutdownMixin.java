package me.snowmii.dlss.mixin;

import me.snowmii.dlss.client.ClientRuntime;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Releases the DLSS path before Minecraft tears its Vulkan device down.
 *
 * {@code Minecraft.close()} ends with {@code windowSurface.close()} and
 * {@code RenderSystem.shutdownRenderer()}, which destroy the swapchain and then the device.
 * Everything DLSS owns - the scene and UI targets, the native evaluation images, and Streamline's
 * DLSS-G present hook - has to be gone by then, so this runs at {@code HEAD}, before the first of
 * Minecraft's own {@code close()} calls.
 *
 * {@code HEAD} rather than an anchor near the device teardown deliberately: the release only has to
 * happen before it, and {@code HEAD} is the point no other mod's injection can be redirected out
 * from under.
 */
@Mixin(Minecraft.class)
public class MinecraftShutdownMixin {
	@Inject(method = "close", at = @At("HEAD"))
	private void mcDlssShutdown(final CallbackInfo info) {
		ClientRuntime.shutdown();
	}
}
