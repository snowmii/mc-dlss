package me.snowmii.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import me.snowmii.dlss.DlssClientRuntime;
import me.snowmii.dlss.DlssWorldPhase;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Scopes the DLSS world phase to {@code LevelRenderer.render}.
 *
 * This method, and not {@code GameRenderer.renderLevel}, is the right boundary: renderLevel also
 * contains hand and item rendering and screen effects, which the effort contract requires at
 * output resolution. Everything inside render belongs to the world scene.
 *
 * The main target is read at HEAD, while the redirect is still inactive, so the phase always
 * measures the real full-size target and never sees its own override.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererWorldPhaseMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void mcDlssBeginWorldPhase(
		final GraphicsResourceAllocator resourceAllocator,
		final DeltaTracker deltaTracker,
		final boolean renderOutline,
		final CameraRenderState cameraState,
		final Matrix4fc modelViewMatrix,
		final GpuBufferSlice terrainFog,
		final Vector4f fogColor,
		final boolean shouldRenderSky,
		final CallbackInfo info
	) {
		final DlssWorldPhase phase = DlssClientRuntime.worldPhase();
		if (phase == null) {
			return;
		}

		final RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		phase.begin(!cameraState.isPanoramicMode, mainTarget);
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void mcDlssEndWorldPhase(
		final GraphicsResourceAllocator resourceAllocator,
		final DeltaTracker deltaTracker,
		final boolean renderOutline,
		final CameraRenderState cameraState,
		final Matrix4fc modelViewMatrix,
		final GpuBufferSlice terrainFog,
		final Vector4f fogColor,
		final boolean shouldRenderSky,
		final CallbackInfo info
	) {
		final DlssWorldPhase phase = DlssClientRuntime.activeWorldPhase();
		if (phase != null) {
			phase.end();
		}
	}
}
