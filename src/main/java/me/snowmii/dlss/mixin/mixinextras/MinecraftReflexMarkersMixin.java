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
 * Emits Reflex/PCL render-submit markers around Minecraft's real render submission:
 * the {@code CommandEncoder.submit()} call at the tail of {@code Minecraft.renderFrame}.
 *
 * <p>The bracket wraps the {@code submit()} invocation itself rather than injecting before and
 * after it, so it can never resolve onto a different call and stays chainable with another mod
 * wrapping the same call site ({@code @WrapOperation} chains, an {@code @Inject} on the same
 * instruction would not). The END marker fires in a {@code finally}, so a submit that throws -
 * which propagates out of renderFrame into the crash path - still closes the bracket the START
 * opened: an open simulation/render bracket is exactly the kind of stale marker that makes
 * latency evidence misleading.
 *
 * <p>The handler stays thin: it reads the active world phase - the render loop's handle to the
 * runtime, null before the DLSS path was built - and delegates; all gating lives in the adapter
 * and the native side. This is the only submission seam in renderFrame: the blit to the
 * surface hands its own encoder to {@code blitFromTexture} and never submits it.
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
