package me.snowmii.dlss.mixin.renderer;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import me.snowmii.dlss.client.ActiveView;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.render.DlssCameraSample;
import me.snowmii.dlss.render.DlssJitterOffset;
import me.snowmii.dlss.render.DlssProjectionJitter;
import me.snowmii.dlss.render.WorldPhase;
import me.snowmii.dlss.render.ui.UiPhase;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4fc;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = GameRenderer.class, priority = 1500)
public class GameRendererMixin {

	@Shadow @Final private RenderTarget mainRenderTarget;

	@Unique
	private final Matrix4f mcDlssJitteredProjection = new Matrix4f();

	@WrapOperation(
		method = "renderLevel",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;",
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

	@WrapMethod(method = "renderItemInHand")
	private void mcDlssBeginHandPhase(
		final CameraRenderState camera,
		final PlayerRenderState player,
		final GpuTextureView depth,
		final Operation<Void> original
	) {
		final UiPhase phase = ClientRuntime.renderLoop().uiPhase();
		if (phase == null) {
			original.call(camera, player, depth);
			return;
		}
		phase.renderHand(this.mainRenderTarget, () -> original.call(camera, player, depth));
	}

	@WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE",
		target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/renderpearl/api/commands/RenderPass;"))
	private RenderPass mcDlssHandTarget(
		final CommandEncoder encoder, final Supplier<String> label,
		final GpuTextureView color, final Optional<Vector4fc> clearColor,
		final GpuTextureView depth, final OptionalDouble clearDepth,
		final Operation<RenderPass> original
	) {
		final UiPhase phase = ClientRuntime.active().activeUiPhase();
		final RenderTarget ui = phase == null ? null : phase.getUiTargetOverride();
		return ui == null ? original.call(encoder, label, color, clearColor, depth, clearDepth)
			: original.call(encoder, label, ui.getColorTextureView(), clearColor, ui.getDepthTextureView(), clearDepth);
	}
}
