package me.snowmii.dlss.mixin;

import me.snowmii.dlss.client.ClientRuntime;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code Minecraft.close()} HEAD, before window/device teardown. Scene/UI targets, native
 * images, and Streamline's present hook must be gone before {@code windowSurface.close()} /
 * {@code RenderSystem.shutdownRenderer()}.
 */
@Mixin(Minecraft.class)
public class MinecraftShutdownMixin {
	@Inject(method = "close", at = @At("HEAD"))
	private void mcDlssShutdown(final CallbackInfo info) {
		ClientRuntime.shutdown();
	}
}
