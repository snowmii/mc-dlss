package me.snowmii.dlss.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.CommandEncoder;
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

@Mixin(Minecraft.class)
public class MinecraftMixin {

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

	@Inject(method = "close", at = @At("HEAD"))
	private void mcDlssShutdown(final CallbackInfo info) {
		ClientRuntime.shutdown();
	}

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


	@ModifyExpressionValue(
		method = "renderFrame",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/platform/FramerateLimitTracker;getFramerateLimit()I"
		)
	)
	private int mcDlssSuppressFramerateLimiter(final int original) {
		final RuntimeControls controls = ClientRuntime.active().activeControls();
		if (controls != null && controls.getSurfacePolicy().getEffective()) {
			return Integer.MAX_VALUE;
		}
		return original;
	}

	@Inject(
		method = "run",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/RenderSystem;pollEvents(Lcom/mojang/blaze3d/platform/SDLEventHandler;)V",
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
			target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;submit()V"
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
