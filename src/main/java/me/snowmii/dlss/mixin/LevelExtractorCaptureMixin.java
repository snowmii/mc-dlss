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
 * Capture interpolated entity pose into object-motion history at
 * {@code LevelExtractor.extractEntity} RETURN — before the world phase opens.
 * Pose is the partial-tick interpolated state, not tick position. Never creates the runtime.
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
