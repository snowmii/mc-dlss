package me.snowmii.dlss.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import me.snowmii.dlss.mrt.HandVelocityWriterBindings;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Installs each map-label submit's hand slot while its glyphs are staged.
 *
 * {@code renderMap} submits the map labels through {@code submitText}, so their
 * {@code TextFeatureRenderer.Submit} records carry the map hand's slot (the submit-constructor
 * copy). The staging seam wraps each {@code PreparedText.visit} call - the moment one submit's
 * glyphs are written - so a hand submit stages its slot only while its own glyphs are staged,
 * and an entity or screen-effect text submit sharing the same group stages nothing. All three
 * visit call sites are covered: the plain label visit and the outline branch's two visits
 * (outline, then offset text). A whole-group bracket cannot express this: mapped 26.2 feature
 * lists mix hand submits with unrelated text submits, so the first submit's slot (or lack of
 * one) would leak across the whole group.
 */
@Mixin(TextFeatureRenderer.class)
public class TextFeatureRendererMotionMixin {
	@WrapOperation(
		method = "buildGroup",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/Font$PreparedText;visit(Lnet/minecraft/client/gui/Font$GlyphVisitor;)V"
		)
	)
	private void mcDlssStageTextSubmit(
		final Font.GlyphVisitor glyphVisitor,
		final Operation<Void> original,
		@Local final TextFeatureRenderer.Submit submit
	) {
		HandVelocityWriterBindings.beginSubmit(submit);
		try {
			original.call(glyphVisitor);
		} finally {
			HandVelocityWriterBindings.endSubmit();
		}
	}
}
