package me.snowmii.dlss.mixin.renderer;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import me.snowmii.dlss.client.ActiveView;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.render.DlssCameraSample;
import me.snowmii.dlss.render.DlssJitterOffset;
import me.snowmii.dlss.render.DlssProjectionJitter;
import me.snowmii.dlss.render.WorldPhase;
import me.snowmii.dlss.render.ui.UiPhase;
import me.snowmii.dlss.stress.StressRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * All {@code GameRenderer} intercepts in one mixin. {@code priority = 1500} so the projection
 * wrap is outermost relative to Sodium ({@code GameRendererMixin.sodium$setProjection}): equal
 * priority orders by classpath, which on some launches gave Sodium the unjittered matrix while
 * DLSS was told the terrain moved, ragging standing-still texel edges.
 *
 * Projection jitter: wraps the first {@code ProjectionMatrixBuffer.getBuffer} call inside
 * {@code renderLevel} to apply the per-frame sub-pixel offset before the world is drawn.
 * The projection at that point already carries view bob and portal/nausea skew — camera motion
 * the reprojection must see. Route is decided before {@code LevelRenderer.render} opens the
 * world phase ({@link WorldPhase#prepare} without opening) so hand/screen-effects rendering
 * still sees the vanilla main target.
 *
 * Main-target override: overrides the {@code mainRenderTarget()} getter so the world phase's
 * low-res scene target and UI window targets are served to callers. The field read is used by
 * GameRenderer itself, bypassing the getter and avoiding a feedback loop. World phase wins over
 * UI windows; real frames never overlap, so precedence is defensive.
 *
 * Hand UI window: brackets {@code renderItemInHand} HEAD/TAIL to redirect hand rendering into
 * its own UI window. The main target is read at HEAD (redirect inactive at that point). Drawing
 * is gated inside vanilla; a closed gate produces an empty clear with no visible effect.
 */
@Mixin(value = GameRenderer.class, priority = 1500)
public class GameRendererMixin {

	// Field read bypasses the overridden getter; avoids seeing the low-res override during jitter.
	@Shadow @Final private RenderTarget mainRenderTarget;

	/** Reused across frames; the render loop is single-threaded and this runs once per frame. */
	@Unique
	private final Matrix4f mcDlssJitteredProjection = new Matrix4f();

	// --- projection jitter ---

	@WrapOperation(
		method = "renderLevel",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;",
			ordinal = 0
		)
	)
	private GpuBufferSlice mcDlssJitterWorldProjection(
		final ProjectionMatrixBuffer buffer,
		final Matrix4f projectionMatrix,
		final Operation<GpuBufferSlice> original
	) {
		final GameRenderer self = (GameRenderer)(Object)this;
		final CameraRenderState cameraState = self.gameRenderState().levelRenderState.cameraRenderState;
		final boolean normalInWorldFrame = !cameraState.isPanoramicMode;
		final Vec3 cameraPosition = cameraState.pos;
		final DlssCameraSample camera = new DlssCameraSample(
			projectionMatrix,
			cameraState.viewRotationMatrix,
			cameraPosition.x,
			cameraPosition.y,
			cameraPosition.z
		);
		StressRuntime.recordCamera(camera);

		final WorldPhase phase = ClientRuntime.renderLoop().worldPhase();
		if (phase == null) {
			return original.call(buffer, projectionMatrix);
		}

		final DlssJitterOffset offset = phase.prepare(normalInWorldFrame, this.mainRenderTarget, camera);
		if (offset == null) {
			return original.call(buffer, projectionMatrix);
		}

		return original.call(buffer, DlssProjectionJitter.apply(projectionMatrix, offset, this.mcDlssJitteredProjection));
	}

	// --- main-target override ---

	@Inject(method = "mainRenderTarget", at = @At("HEAD"), cancellable = true)
	private void mcDlssRedirectWorldTarget(final CallbackInfoReturnable<RenderTarget> info) {
		final ActiveView active = ClientRuntime.active();
		final WorldPhase worldPhase = active.activeWorldPhase();
		final UiPhase uiPhase = active.activeUiPhase();
		final RenderTarget override = ClientRuntime.resolveActiveTarget(
			worldPhase == null ? null : worldPhase.getWorldTargetOverride(),
			uiPhase == null ? null : uiPhase.getUiTargetOverride()
		);
		if (override != null) {
			info.setReturnValue(override);
		}
	}

	// --- hand UI window ---

	@Inject(method = "renderItemInHand", at = @At("HEAD"))
	private void mcDlssBeginHandPhase(final CallbackInfo info) {
		final UiPhase phase = ClientRuntime.renderLoop().uiPhase();
		if (phase == null) {
			return;
		}
		final RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		phase.beginHand(mainTarget);
	}

	@Inject(method = "renderItemInHand", at = @At("TAIL"))
	private void mcDlssEndHandPhase(final CallbackInfo info) {
		final UiPhase phase = ClientRuntime.active().activeUiPhase();
		if (phase != null) {
			phase.end();
		}
	}
}
