package me.snowmii.dlss.mixin;

import me.snowmii.dlss.mrt.MovingBlockVelocityWriterBindings;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Installs one piston moving block's identity on the thread while its quads are staged into the
 * moving-block render types.
 *
 * {@code MovingBlockFeatureRenderer.buildGroup} tesselates one {@code MovingBlockFeatureRenderer.Submit}
 * at a time - each carrying the exact {@code MovingBlockRenderState} object the block-entity
 * dispatcher's capture seam bound to a block-position id. This redirect brackets each
 * {@code ModelBlockRenderer.tesselateBlock} call with that id, so the shared
 * {@code RenderTypeFeatureRenderer$Group} draw boundary (see {@code RenderTypeFeatureRendererMotionMixin})
 * sees the current moving block while its solid and cutout draws are created and binds the
 * draw -> id -> ExecuteInfo chain the prepared-draw writer reads. A render state with no bound
 * id - a falling block, which rides the same feature renderer but never goes through the piston
 * capture seam, or an outline submit - keeps the exact source tesselation with no context and
 * no identity, so its draws stay vanilla.
 */
@Mixin(MovingBlockFeatureRenderer.class)
public class MovingBlockFeatureRendererMotionMixin {
	@Redirect(
		method = "buildGroup",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock(" +
				"Lnet/minecraft/client/renderer/block/BlockQuadOutput;" +
				"FFF" +
				"Lnet/minecraft/client/renderer/block/BlockAndTintGetter;" +
				"Lnet/minecraft/core/BlockPos;" +
				"Lnet/minecraft/world/level/block/state/BlockState;" +
				"Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;" +
				"J)V"
		)
	)
	private void mcDlssBeginMovingBlockDraw(
		final ModelBlockRenderer blockRenderer,
		final BlockQuadOutput output,
		final float x,
		final float y,
		final float z,
		final BlockAndTintGetter level,
		final BlockPos pos,
		final BlockState blockState,
		final BlockStateModel model,
		final long seed
	) {
		final Long id = level instanceof MovingBlockRenderState moving
			? MovingBlockVelocityWriterBindings.movingBlockId(moving)
			: null;
		if (id == null) {
			blockRenderer.tesselateBlock(output, x, y, z, level, pos, blockState, model, seed);
			return;
		}

		MovingBlockVelocityWriterBindings.beginMovingBlock(id);
		try {
			blockRenderer.tesselateBlock(output, x, y, z, level, pos, blockState, model, seed);
		} finally {
			MovingBlockVelocityWriterBindings.endMovingBlock();
		}
	}
}
