package me.snowmii.dlss.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.VertexConsumer;
import me.snowmii.dlss.mrt.HandVelocityWriterBindings;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Installs each custom-geometry submit's hand slot while its pose-baked quads are staged.
 *
 * {@code renderMap} submits the map background, map texture, and decorations through
 * {@code submitCustomGeometry}, so their {@code CustomFeatureRenderer.Submit} records carry the
 * map hand's slot (the submit-constructor copy). The staging seam wraps the per-submit
 * {@code getVertexBuilder} call - the moment exactly one submit's geometry is written - so a
 * hand submit stages its slot only while its own quads are staged, and an entity or
 * screen-effect submit sharing the same group stages nothing. A whole-group bracket cannot
 * express this: mapped 26.2 feature lists mix hand submits with unrelated custom submits, so
 * the first submit's slot (or lack of one) would leak across the whole group.
 */
@Mixin(CustomFeatureRenderer.class)
public class CustomFeatureRendererMotionMixin {
	@WrapOperation(
		method = "buildGroup",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/feature/CustomFeatureRenderer;getVertexBuilder(Lnet/minecraft/client/renderer/rendertype/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"
		)
	)
	private VertexConsumer mcDlssStageCustomSubmit(
		final CustomFeatureRenderer renderer,
		final RenderType renderType,
		final Operation<VertexConsumer> original,
		@Local final CustomFeatureRenderer.Submit submit
	) {
		HandVelocityWriterBindings.beginSubmit(submit);
		try {
			return original.call(renderer, renderType);
		} finally {
			HandVelocityWriterBindings.endSubmit();
		}
	}
}
