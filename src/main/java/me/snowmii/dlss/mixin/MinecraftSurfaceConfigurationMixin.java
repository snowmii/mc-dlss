package me.snowmii.dlss.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.client.RuntimeControls;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Makes Minecraft's own surface reconfigure apply the FG present-mode policy.
 *
 * When FG is active, the swapchain must not present FIFO, and Minecraft picks its present mode
 * in {@code renderFrame}'s reconfigure block from the vsync option:
 * {@code GpuSurface.PresentMode.getSupportedVsyncMode(..., this.options.enableVsync().get())}.
 * The decision is the mod-owned policy's ([me.snowmii.dlss.fg.FgSurfacePolicy]): this handler
 * only reads it through the active controls and passes the stored value through untouched when
 * there are none - the policy answers false while FG is active, so the block selects a non-FIFO
 * mode ({@code IMMEDIATE}/{@code MAILBOX} first), and because the stored option itself is never
 * written its value survives an FG on/off cycle unchanged, so the stored option survives an FG on/off cycle unchanged.
 *
 * The read is modified in place rather than the stored option set so no other reader of the
 * option can observe a mutated value, and the whole reconfigure path stays Minecraft's own.
 * {@code ModifyExpressionValue} chains with any other mod modifying the same read.
 *
 * Nothing here creates the DLSS path: the policy is read through the active controls, so a
 * session that never built the runtime answers with the stored value exactly as vanilla would.
 */
@Mixin(Minecraft.class)
public class MinecraftSurfaceConfigurationMixin {
	@ModifyExpressionValue(
		method = "renderFrame",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;"
		)
	)
	private Object mcDlssFgVsyncRead(final Object original) {
		final RuntimeControls controls = ClientRuntime.active().activeControls();
		if (controls == null) {
			return original;
		}
		return controls.getSurfacePolicy().effectiveVsyncEnabled((Boolean) original);
	}
}
