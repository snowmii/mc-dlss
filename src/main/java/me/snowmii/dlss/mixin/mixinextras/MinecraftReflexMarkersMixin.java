package me.snowmii.dlss.mixin.mixinextras;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.CommandEncoder;
import me.snowmii.streamline.StreamlineSession;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.render.WorldPhase;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Reflex/PCL render-submit markers around {@code CommandEncoder.submit()} at the tail of
 * {@code Minecraft.renderFrame}.
 *
 * Wrap the invoke (not HEAD/TAIL injects) so the site stays unique and chainable. END in
 * {@code finally} so a throwing submit still closes the bracket. Blit encoder is never
 * submitted; this is the only submit in {@code renderFrame}.
 */
@Mixin(Minecraft.class)
public class MinecraftReflexMarkersMixin {
	@WrapOperation(
		method = "renderFrame",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/CommandEncoder;submit()V"
		)
	)
	private void mcDlssReflexRenderSubmit(final CommandEncoder encoder, final Operation<Void> original) {
		final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		if (phase != null) {
			phase.reflexMarker(StreamlineSession.ReflexMarkerType.RENDER_SUBMIT_START);
			phase.submitStart();
		}
		try {
			original.call(encoder);
		} finally {
			if (phase != null) {
				phase.submitEnd();
				phase.reflexMarker(StreamlineSession.ReflexMarkerType.RENDER_SUBMIT_END);
			}
		}
	}
}
