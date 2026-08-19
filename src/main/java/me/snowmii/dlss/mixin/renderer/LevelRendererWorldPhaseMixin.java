package me.snowmii.dlss.mixin.renderer;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.stress.StressRuntime;
import me.snowmii.dlss.render.WorldPhase;
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
 * World phase = {@code LevelRenderer.render}. Hand/item draw after this method, so they stay
 * full-res. Read main at HEAD before the redirect is active.
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
		final WorldPhase phase = ClientRuntime.renderLoop().worldPhase();
		if (phase == null) {
			return;
		}

		final RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		phase.begin(!cameraState.isPanoramicMode, mainTarget);
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void mcDlssRenderStressPass(
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
		// Still inside the world phase - the low-resolution scene target on a DLSS frame, the real
		// main target on a vanilla one - so the stress pass pays its cost at whatever resolution
		// the world was actually rendered at, which is the comparison it exists to make, and DLSS
		// upscales the finished scene (stress pass included) rather than a pre-effect one. The
		// phase is handed along only to supply the velocity-MRT write context: null in exactly
		// the sessions without a phase, so vanilla and camera-only frames keep the one-target
		// stress pass and the stress pass itself still renders on every frame. The phase closes
		// here, before GameRenderer submits hand/item features, so they use vanilla's full-size
		// target and unjittered HUD projection.
		final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		StressRuntime.render(Minecraft.getInstance().gameRenderer.mainRenderTarget(), phase);
		if (phase != null) {
			phase.end();
		}
	}
}
