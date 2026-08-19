package me.snowmii.dlss.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.CommandEncoder;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.client.RuntimeControls;
import me.snowmii.dlss.render.WorldPhase;
import me.snowmii.streamline.StreamlineSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * All {@code Minecraft} intercepts in one mixin.
 *
 * Level change: resets DLSS history on {@code setLevel} and {@code clearClientLevel}. Camera
 * motion continuity does not cover a world swap — coordinates can match while every surface is
 * new.
 *
 * Shutdown: injects at {@code close} HEAD, before window/device teardown. Scene/UI targets,
 * native images, and Streamline's present hook must be released before
 * {@code windowSurface.close()} and {@code RenderSystem.shutdownRenderer()}.
 *
 * Surface configuration: overrides the vsync flag read during {@code renderFrame} so FG can
 * suppress FIFO while armed. The stored setting is never written, so it survives FG on/off
 * cycles transparently.
 *
 * Reflex/PCL markers: input sample after GLFW poll in {@code run}; simulation START/END
 * bracketing {@code runTick}; render-submit START/END wrapping {@code CommandEncoder.submit()}
 * in {@code renderFrame}. The submit wrap uses {@code finally} so a throwing submit still
 * closes the bracket.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {

	// --- level change ---

	@Inject(method = "setLevel", at = @At("HEAD"))
	private void mcDlssResetHistoryOnLevelLoad(final ClientLevel level, final CallbackInfo info) {
		mcDlssResetHistory();
	}

	@Inject(method = "clearClientLevel", at = @At("HEAD"))
	private void mcDlssResetHistoryOnLevelClear(final Screen screen, final CallbackInfo info) {
		mcDlssResetHistory();
	}

	@Unique
	private void mcDlssResetHistory() {
		final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		if (phase != null) {
			phase.resetHistory();
		}
	}

	// --- shutdown ---

	@Inject(method = "close", at = @At("HEAD"))
	private void mcDlssShutdown(final CallbackInfo info) {
		ClientRuntime.shutdown();
	}

	// --- surface configuration ---

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

	// --- Reflex/PCL markers ---

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
