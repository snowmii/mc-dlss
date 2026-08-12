package me.snowmii.dlss.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import me.snowmii.dlss.render.DlssCameraSample;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.render.DlssJitterOffset;
import me.snowmii.dlss.render.DlssProjectionJitter;
import me.snowmii.dlss.pass.StressRuntime;
import me.snowmii.dlss.render.WorldPhase;
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

/**
 * Jitters the world projection of an eligible DLSS frame.
 *
 * {@code GameRenderer.renderLevel} uploads exactly one projection for the world scene and a
 * separate one for the hand, item, and 3D crosshair immediately afterwards. Wrapping the
 * world upload therefore reaches the world and nothing else, which is what the contract
 * requires: presentation content stays unjittered at output resolution.
 *
 * This is also where the frame's route is decided. The upload happens before
 * {@code LevelRenderer.render} opens the world phase, so the phase cannot be open yet - opening
 * it this early would put the hand and screen effects behind the low-resolution override too.
 * {@link WorldPhase#prepare} decides route and jitter without opening anything, and the
 * later {@code begin} consumes that decision instead of repeating it, so the session still sees
 * one frame decision per frame.
 */
@Mixin(GameRenderer.class)
public class GameRendererProjectionJitterMixin {
	/**
	 * Read instead of {@code mainRenderTarget()}, which
	 * {@link me.snowmii.dlss.mixin.GameRendererWorldTargetMixin} overrides while a phase is open.
	 * A frame whose {@code LevelRenderer.render} threw leaves a phase open past its tail, and
	 * the next frame would then measure the low-resolution scene target as if it were the
	 * window: the route would see the wrong output size, and the scene target would become the
	 * destination that frame later presents into. The field is what GameRenderer's own uses
	 * read, and it is never overridden.
	 */
	@Shadow
	@Final
	private RenderTarget mainRenderTarget;

	/** Reused across frames; the render loop is single-threaded and this runs once per frame. */
	@Unique
	private final Matrix4f mcDlssJitteredProjection = new Matrix4f();

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
		// The projection handed to this handler is the one about to be uploaded, so it already
		// carries view bob and the portal/nausea skew - camera motion the reprojection must see.
		// The camera position travels separately because the world is rendered camera-relative.
		final Vec3 cameraPosition = cameraState.pos;
		final DlssCameraSample camera = new DlssCameraSample(
			projectionMatrix,
			cameraState.viewRotationMatrix,
			cameraPosition.x,
			cameraPosition.y,
			cameraPosition.z
		);
		// Recorded before the phase is consulted, because the stress pass runs in sessions where
		// DLSS never starts and the phase is null in exactly those sessions.
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
}
