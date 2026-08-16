package me.snowmii.dlss.mixin;

import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.render.WorldPhase;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Feeds every visible entity's interpolated render position into the object-motion history.
 *
 * {@code LevelExtractor.extractVisibleEntities} is the visible-entity extraction pass: it runs
 * from {@code GameRenderer.extract} before {@code LevelRenderer.render} opens the DLSS world
 * phase. Its one call to {@code extractEntity(Entity, float)} already pairs the live
 * {@code Entity} (whose {@code getId()} is the stable history key) with the returned
 * {@code EntityRenderState} (whose {@code x}/{@code y}/{@code z} doubles are the partial-tick
 * interpolated pose the geometry will actually draw, not the tick position). Injecting at that
 * private helper's return avoids depending on the caller's long, version-fragile local-variable
 * table; the handler needs only target arguments and return value, so Sponge captures no locals.
 *
 * The capture delegates read-only through {@link ClientRuntime#active()}, never creating the
 * world phase: building the DLSS path is reserved to {@code LevelRenderer.render} HEAD. A phase
 * that does not exist yet - the first frame of a session, or a session that never enabled DLSS -
 * receives no capture, which history treats as a first observation rather than stale state.
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorCaptureMixin {
	@Inject(method = "extractEntity", at = @At("RETURN"))
	private void mcDlssCaptureVisibleEntity(
		final Entity entity,
		final float partialTickTime,
		final CallbackInfoReturnable<EntityRenderState> info
	) {
		final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		final EntityRenderState state = info.getReturnValue();
		if (phase != null && state != null) {
			phase.captureEntity(state, entity.getId(), state.x, state.y, state.z);
		}
	}
}
