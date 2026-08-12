package me.snowmii.dlss.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.render.WorldPhase;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Redirects the world phase to the mod's low-resolution scene target and closes the phase right
 * after the hand draw.
 *
 * {@code GameRenderer.mainRenderTarget()} is a plain getter, and GameRenderer's own uses read
 * the private field directly. Overriding the getter therefore reaches exactly the callers
 * outside GameRenderer - which, during an open world phase, are the LevelRenderer frame graph,
 * its screen-size derived targets, the sky pass, and the hand/item draw (its feature passes
 * resolve {@code OutputTarget.MAIN_TARGET} through this getter). The phase closes in
 * {@code renderLevel} immediately after the {@code renderItemInHand} submission, so hand and
 * item render into the low-resolution scene at render resolution; screen effects, the 3D
 * crosshair, post chains, GUI clear, screenshots, and presentation run after the phase is
 * closed and stay full-resolution.
 *
 * Never initializes the DLSS runtime: this runs on every frame from many call sites, so it only
 * reads a phase that {@code LevelRenderer.render} already opened.
 */
@Mixin(GameRenderer.class)
public class GameRendererWorldTargetMixin {
	@Inject(method = "mainRenderTarget", at = @At("HEAD"), cancellable = true)
	private void mcDlssRedirectWorldTarget(final CallbackInfoReturnable<RenderTarget> info) {
		final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		if (phase == null) {
			return;
		}

		final RenderTarget override = phase.getWorldTargetOverride();
		if (override != null) {
			info.setReturnValue(override);
		}
	}

	/**
	 * Closes the world phase immediately after the existing hand/item draw.
	 *
	 * {@code renderItemInHand} is the last draw inside the low-resolution window: everything it
	 * submits resolves its target through the {@code mainRenderTarget()} getter this mixin
	 * overrides, so while the phase is open the hand lands in the scene target DLSS upscales.
	 * Closing here - after the hand, before {@code ScreenEffectRenderer.submit} - evaluates DLSS
	 * over the finished scene (world, stress pass, and hand) and leaves screen effects and the 3D
	 * crosshair their existing post-DLSS, full-resolution route. The field-based depth clear
	 * before the hand is untouched, so it still clears the full-resolution main target exactly as
	 * vanilla does.
	 *
	 * Adds no draw and no duplicate: it only moves the {@code WorldPhase.end} the LevelRenderer
	 * tail used to call, and {@code end} is a no-op once the phase is already closed.
	 */
	@WrapOperation(
		method = "renderLevel",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;FLorg/joml/Matrix4fc;)V"
		)
	)
	private void mcDlssRenderHandAndEnd(
		final GameRenderer renderer,
		final CameraRenderState cameraState,
		final float deltaPartialTick,
		final Matrix4fc modelViewMatrix,
		final Operation<Void> original
	) {
		final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		try {
			original.call(renderer, cameraState, deltaPartialTick, modelViewMatrix);
			if (phase != null) {
				phase.end();
			}
		} catch (RuntimeException | Error failure) {
			if (phase != null) {
				phase.resetHistory();
			}
			throw failure;
		}
	}
}
