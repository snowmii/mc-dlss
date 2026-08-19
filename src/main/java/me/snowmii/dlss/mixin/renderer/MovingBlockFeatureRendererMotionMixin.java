package me.snowmii.dlss.mixin.renderer;

import com.llamalad7.mixinextras.sugar.Local;
import java.util.List;
import me.snowmii.dlss.render.mrt.MovingBlockVelocityWriterBindings;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * One piston moving block's identity while its quads stage. Falling blocks ride the same
 * renderer but never go through piston capture — identity-less, vanilla tesselation.
 */
@Mixin(MovingBlockFeatureRenderer.class)
public class MovingBlockFeatureRendererMotionMixin {
	// Deliberately an inject pair and never a redirect on tesselateBlock: fabric-renderer-api-v1
	// redirects that exact call, and two redirects on one call site are an injection failure.
	// The anchor is the void PoseStack.setIdentity() that opens each submit's iteration - fabric's
	// mixin never touches it - so the id is installed before the quad outputs that stage this
	// submit's geometry, and no later than the next submit's own install.
	@Inject(
		method = "buildGroup",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/PoseStack;setIdentity()V",
			shift = At.Shift.AFTER
		)
	)
	private void mcDlssBeginMovingBlockDraw(
		final FeatureFrameContext context,
		final List<MovingBlockFeatureRenderer.Submit> submits,
		final CallbackInfo info,
		@Local(name = "movingBlockRenderState") final MovingBlockRenderState movingBlockRenderState
	) {
		final Long id = MovingBlockVelocityWriterBindings.movingBlockId(movingBlockRenderState);
		if (id == null) {
			// A falling block or outline submit rides the same feature renderer but never went
			// through the piston capture seam: it must stage with no identity, so the previous
			// submit's id is cleared rather than left installed.
			MovingBlockVelocityWriterBindings.endMovingBlock();
			return;
		}
		MovingBlockVelocityWriterBindings.beginMovingBlock(id);
	}

	@Inject(method = "buildGroup", at = @At("RETURN"))
	private void mcDlssEndMovingBlockDraw(
		final FeatureFrameContext context,
		final List<MovingBlockFeatureRenderer.Submit> submits,
		final CallbackInfo info
	) {
		MovingBlockVelocityWriterBindings.endMovingBlock();
	}
}
