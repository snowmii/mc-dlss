package me.snowmii.dlss.mixin.renderer;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.render.WorldPhase;
import me.snowmii.dlss.render.ui.UiPhase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * World phase = {@code LevelRenderer.render}. Hand/item draw after this method, so they stay
 * full-res. Read main at HEAD before the redirect is active.
 *
 * Minecraft 26.3 dropped {@code DeltaTracker} and the model-view matrix from this signature and
 * added a second trailing boolean.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererWorldPhaseMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void mcDlssBeginWorldPhase(
		final GraphicsResourceAllocator resourceAllocator,
		final boolean renderOutline,
		final CameraRenderState cameraState,
		final GpuBufferSlice terrainFog,
		final Vector4f fogColor,
		final boolean shouldRenderSky,
		final boolean unusedTrailingFlag,
		final CallbackInfo info
	) {
		final WorldPhase phase = ClientRuntime.renderLoop().worldPhase();
		if (phase == null) {
			return;
		}

		final RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		phase.begin(!cameraState.isPanoramicMode, mainTarget);
	}

	@Inject(method = "executeSeeThrough", at = @At("HEAD"), cancellable = true)
	private void mcDlssExecuteSeeThroughNative(
		final FeatureRenderDispatcher.PreparedFrame preparedFrame,
		final RenderTarget target,
		final CallbackInfo info
	) {
		final WorldPhase worldPhase = ClientRuntime.active().activeWorldPhase();
		if (worldPhase == null || !worldPhase.isOpen()) {
			return;
		}
		final RenderTarget mainTarget = worldPhase.getDestinationTarget();
		if (mainTarget == null) {
			return;
		}
		final UiPhase uiPhase = ClientRuntime.renderLoop().uiPhase();
		if (uiPhase == null) {
			return;
		}
		final RenderTarget uiTarget = uiPhase.beginSeeThrough(mainTarget);
		if (uiTarget == null) {
			return;
		}

		try {
			final RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "See through features (DLSS Native)")
				.withColorAttachment(uiTarget.getColorTextureView())
				.build();
			final CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			try (final RenderPass pass = encoder.createRenderPass(descriptor)) {
				RenderSystem.backupProjectionMatrix();
				final GpuBufferSlice unjittered = worldPhase.getUnjitteredProjection();
				if (unjittered != null) {
					RenderSystem.setProjectionMatrix(unjittered, ProjectionType.PERSPECTIVE);
				}
				try {
					RenderSystem.bindDefaultUniforms(pass);
					preparedFrame.executeSeeThrough(pass);
				} finally {
					RenderSystem.restoreProjectionMatrix();
				}
			}
		} finally {
			uiPhase.endSeeThrough();
		}
		info.cancel();
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void mcDlssEndWorldPhase(final CallbackInfo info) {
		final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		if (phase != null) phase.end();
	}
}
