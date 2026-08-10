package me.snowmii.dlss.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import me.snowmii.dlss.mrt.EntityVelocityWriterBindings;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes CPU-baked entity geometry one draw per entity before RenderTypeFeatureRenderer's normal
 * same-render-type consolidation can merge it. A draw-wide object uniform is only correct after
 * this boundary; non-entity submits keep vanilla consolidation untouched.
 */
@Mixin(targets = "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group")
public class RenderTypeFeatureRendererMotionMixin {
	@Shadow private StagedVertexBuffer.@Nullable Draw lastDraw;
	@Shadow @Nullable private RenderType lastRenderType;

	@Inject(method = "getVertexBuilder", at = @At("HEAD"))
	private void mcDlssBeginEntityDraw(final RenderType renderType, final CallbackInfoReturnable<VertexConsumer> info) {
		if (EntityVelocityWriterBindings.beginDraw(renderType)) {
			// The normal fast path and the reorder lookup below both reuse one draw for a
			// consecutive render type. Clearing these identities starts a fresh draw; the list
			// lookup redirect also disables the reorder-side reuse.
			this.lastDraw = null;
			this.lastRenderType = null;
		}
	}

	@Inject(method = "getVertexBuilder", at = @At("RETURN"))
	private void mcDlssEndEntityDraw(final RenderType renderType, final CallbackInfoReturnable<VertexConsumer> info) {
		EntityVelocityWriterBindings.endDraw();
	}

	@Redirect(
		method = "getOrAddDraw",
		at = @At(value = "INVOKE", target = "Ljava/util/List;indexOf(Ljava/lang/Object;)I")
	)
	private int mcDlssDisableEntityReorder(final List<?> renderTypes, final Object preparedRenderType) {
		return EntityVelocityWriterBindings.consolidationIndex(renderTypes, preparedRenderType);
	}
}
