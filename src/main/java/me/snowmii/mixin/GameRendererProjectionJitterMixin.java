package me.snowmii.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import me.snowmii.dlss.DlssClientRuntime;
import me.snowmii.dlss.DlssJitterOffset;
import me.snowmii.dlss.DlssProjectionJitter;
import me.snowmii.dlss.DlssWorldPhase;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Jitters the world projection of an eligible DLSS frame.
 *
 * {@code GameRenderer.renderLevel} uploads exactly one projection for the world scene and a
 * separate one for the hand, item, and 3D crosshair immediately afterwards. Redirecting the
 * world upload therefore reaches the world and nothing else, which is what the contract
 * requires: presentation content stays unjittered at output resolution.
 *
 * This is also where the frame's route is decided. The upload happens before
 * {@code LevelRenderer.render} opens the world phase, so the phase cannot be open yet - opening
 * it this early would put the hand and screen effects behind the low-resolution override too.
 * {@link DlssWorldPhase#prepare} decides route and jitter without opening anything, and the
 * later {@code begin} consumes that decision instead of repeating it, so the session still sees
 * one frame decision per frame.
 */
@Mixin(GameRenderer.class)
public class GameRendererProjectionJitterMixin {
	/**
	 * Read instead of {@code mainRenderTarget()}, which
	 * {@link me.snowmii.mixin.GameRendererWorldTargetMixin} overrides while a phase is open.
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

	@Redirect(
		method = "renderLevel",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
		)
	)
	private GpuBufferSlice mcDlssJitterWorldProjection(final ProjectionMatrixBuffer buffer, final Matrix4f projectionMatrix) {
		final DlssWorldPhase phase = DlssClientRuntime.worldPhase();
		if (phase == null) {
			return buffer.getBuffer(projectionMatrix);
		}

		final GameRenderer self = (GameRenderer)(Object)this;
		final boolean normalInWorldFrame = !self.gameRenderState().levelRenderState.cameraRenderState.isPanoramicMode;
		final DlssJitterOffset offset = phase.prepare(normalInWorldFrame, this.mainRenderTarget);
		if (offset == null) {
			return buffer.getBuffer(projectionMatrix);
		}

		return buffer.getBuffer(DlssProjectionJitter.apply(projectionMatrix, offset, this.mcDlssJitteredProjection));
	}
}
