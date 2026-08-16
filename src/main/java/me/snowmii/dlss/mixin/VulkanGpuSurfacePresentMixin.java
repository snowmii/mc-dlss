package me.snowmii.dlss.mixin;

import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.render.WorldPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanGpuSurface.class)
public class VulkanGpuSurfacePresentMixin {
    @Inject(method = "present()V", at = @At("HEAD"))
    private void mcDlssPresentStart(final CallbackInfo ci) {
        final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
        if (phase != null) phase.presentStart();
    }

    @Inject(method = "present()V", at = @At("RETURN"))
    private void mcDlssPresentEnd(final CallbackInfo ci) {
        final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
        if (phase != null) phase.presentEnd();
    }
}
