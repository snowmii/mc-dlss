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
 * Jitters the world projection only. {@code renderLevel} uploads a separate unjittered matrix
 * for hand/item/3D crosshair immediately after.
 *
 * Route is decided here, before {@code LevelRenderer.render} opens the world phase:
 * {@link WorldPhase#prepare} without opening. Opening this early would put hand and screen
 * effects behind the low-res override.
 *
 * {@code priority = 1500} so this wrap is outermost vs Sodium
 * ({@code GameRendererMixin.sodium$setProjection}). Equal priority orders by classpath: some
 * launches gave Sodium the unjittered matrix while DLSS was told the terrain moved, which
 * rags standing-still texel edges.
 */
@Mixin(value = GameRenderer.class, priority = 1500)
public class GameRendererProjectionJitterMixin {
	/**
	 * Read the field, not {@code mainRenderTarget()}: the getter is overridden while a phase
	 * is open. A leaked open phase (exception past {@code LevelRenderer.render} TAIL) would
	 * size the next frame against the low-res scene target.
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
