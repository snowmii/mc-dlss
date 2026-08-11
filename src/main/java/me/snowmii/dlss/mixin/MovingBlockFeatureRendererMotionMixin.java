package me.snowmii.dlss.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import java.util.List;
import me.snowmii.dlss.mrt.MovingBlockVelocityWriterBindings;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Installs one piston moving block's identity on the thread while its quads are staged into the
 * moving-block render types.
 *
 * {@code MovingBlockFeatureRenderer.buildGroup} tesselates one {@code MovingBlockFeatureRenderer.Submit}
 * at a time - each carrying the exact {@code MovingBlockRenderState} object the block-entity
 * dispatcher's capture seam bound to a block-position id. This installs that id at the start of
 * each submit's iteration and clears it when the group ends, so the shared
 * {@code RenderTypeFeatureRenderer$Group} draw boundary (see {@code RenderTypeFeatureRendererMotionMixin})
 * sees the current moving block while its solid and cutout draws are created and binds the
 * draw -> id -> ExecuteInfo chain the prepared-draw writer reads. A render state with no bound
 * id - a falling block, which rides the same feature renderer but never goes through the piston
 * capture seam, or an outline submit - keeps the exact source tesselation with no context and
 * no identity, so its draws stay vanilla.
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
		@Local final MovingBlockRenderState movingBlockRenderState
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
