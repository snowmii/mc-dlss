package me.snowmii.dlss.mixin;

import me.snowmii.streamline.NativeApi;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.render.WorldPhase;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Emits input-sample and simulation Reflex/PCL markers at Minecraft's real input and
 * simulation seams.
 *
 * <p>The input seam is {@code RenderSystem.pollEvents()} inside {@code Minecraft.run}: the
 * GLFW poll is where the frame's input is actually sampled, and the marker fires right after
 * it, on the frame whose simulation picks that input up. The simulation seam brackets
 * {@code Minecraft.runTick}: SIMULATION_START at the method head and SIMULATION_END at the
 * {@code renderFrame} call, so the bracket covers the packet processing and tick loop - the
 * real simulation work - and closes before the frame's rendering begins, which is where the
 * render-submit bracket (the mixinextras sibling in this package) opens. Anchoring the END on
 * the {@code renderFrame} invocation rather than the method RETURN keeps the simulation
 * bracket from swallowing the render work; the invocation is the runTick body's only call to
 * {@code renderFrame}, so the injection point is unique in the method.
 *
 * <p>Both handlers stay thin: they read the active world phase - the render loop's handle to
 * the runtime, null before the DLSS path was built and outside the phase's own window - and
 * delegate. All gating (READY session, retained frame token) lives in the adapter and the
 * native side, and a null phase is simply no call, exactly like the present-seam mixin.
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
		if (phase != null) phase.reflexMarker(NativeApi.ReflexMarkerType.SIMULATION_START);
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
		if (phase != null) phase.reflexMarker(NativeApi.ReflexMarkerType.SIMULATION_END);
	}
}
