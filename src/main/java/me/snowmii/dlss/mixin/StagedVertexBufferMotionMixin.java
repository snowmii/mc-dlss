package me.snowmii.dlss.mixin;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import me.snowmii.dlss.mrt.EntityVelocityWriterBindings;
import me.snowmii.dlss.mrt.HandVelocityWriterBindings;
import me.snowmii.dlss.mrt.MovingBlockVelocityWriterBindings;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Carries per-entity, per-moving-block, and per-hand identity across staged draws and their uploaded ExecuteInfo records. */
@Mixin(StagedVertexBuffer.class)
public class StagedVertexBufferMotionMixin {
	@Inject(
		method = "appendDraw(Lcom/mojang/blaze3d/vertex/VertexFormat;Lcom/mojang/blaze3d/PrimitiveTopology;Lcom/mojang/blaze3d/vertex/VertexSorting;)Lnet/minecraft/client/renderer/StagedVertexBuffer$Draw;",
		at = @At("RETURN")
	)
	private void mcDlssBindEntityDraw(
		final VertexFormat format,
		final PrimitiveTopology primitiveTopology,
		final @Nullable VertexSorting quadSorting,
		final CallbackInfoReturnable<StagedVertexBuffer.Draw> info
	) {
		final StagedVertexBuffer.Draw draw = info.getReturnValue();
		if (draw != null) {
			EntityVelocityWriterBindings.bindDraw(draw);
			MovingBlockVelocityWriterBindings.bindDraw(draw);
			HandVelocityWriterBindings.bindDraw(draw);
		}
	}

	@Inject(
		method = "getExecuteInfo(Lnet/minecraft/client/renderer/StagedVertexBuffer$Draw;)Lnet/minecraft/client/renderer/StagedVertexBuffer$ExecuteInfo;",
		at = @At("RETURN")
	)
	private void mcDlssBindEntityExecuteInfo(
		final StagedVertexBuffer.Draw draw,
		final CallbackInfoReturnable<StagedVertexBuffer.ExecuteInfo> info
	) {
		EntityVelocityWriterBindings.bindExecuteInfo(draw, info.getReturnValue());
		MovingBlockVelocityWriterBindings.bindExecuteInfo(draw, info.getReturnValue());
		HandVelocityWriterBindings.bindExecuteInfo(draw, info.getReturnValue());
	}

	@Inject(method = "endDraw", at = @At("HEAD"))
	private void mcDlssClearEntityDraws(final CallbackInfo info) {
		EntityVelocityWriterBindings.clearFrame();
		MovingBlockVelocityWriterBindings.clearFrame();
		HandVelocityWriterBindings.clearFrame();
	}
}
