package me.snowmii.dlss.mixin;

import me.snowmii.streamline.StreamlineSession;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.render.WorldPhase;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reflex/PCL input and simulation markers.
 *
 * Input: after {@code RenderSystem.pollEvents()} (GLFW poll) inside {@code Minecraft.run}.
 * Simulation: START at {@code runTick} HEAD, END BEFORE {@code renderFrame} — not RETURN, or
 * the bracket swallows render. Unique {@code renderFrame} call in that method.
 */
@Mixin(Minecraft.class)
public class MinecraftReflexMarkersMixin {
	@Inject(
		method = "run",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/RenderSystem;pollEvents()V",
			shift = At.Shift.AFTER
		)
	)
	private void mcDlssReflexInputSample(final CallbackInfo ci) {
		final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		if (phase != null) phase.reflexInputSample();
	}

	@Inject(method = "runTick", at = @At("HEAD"))
	private void mcDlssReflexSimulateStart(final CallbackInfo ci) {
		final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		if (phase != null) phase.reflexMarker(StreamlineSession.ReflexMarkerType.SIMULATION_START);
	}

	@Inject(
		method = "runTick",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V",
			shift = At.Shift.BEFORE
		)
	)
	private void mcDlssReflexSimulateEnd(final CallbackInfo ci) {
		final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		if (phase != null) phase.reflexMarker(StreamlineSession.ReflexMarkerType.SIMULATION_END);
	}
}
