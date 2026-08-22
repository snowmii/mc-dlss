package me.snowmii.dlss.mixin.renderer;

import com.mojang.renderpearl.api.commands.RenderPass;
import me.snowmii.dlss.render.mrt.EntityVelocityRender;
import me.snowmii.dlss.render.mrt.MovingBlockVelocityRender;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces supported main-target entity and piston moving-block draws with the two-attachment
 * velocity pass when that path is viable.
 *
 * Minecraft 26.3 draws into a shared {@link RenderPass} already open by {@code LevelRenderer},
 * so the previous private two-attachment pass cannot open while that pass is live. Until the
 * writers are redesigned for the shared pass, this inject keeps the vanilla draw (no cancel).
 */
@Mixin(PreparedRenderType.class)
public class PreparedRenderTypeMotionMixin {
	@SuppressWarnings("ConstantConditions")
	@Inject(
		method = "drawFromBuffer(Lnet/minecraft/client/renderer/StagedVertexBuffer$ExecuteInfo;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void mcDlssDrawEntityVelocity(
		final StagedVertexBuffer.ExecuteInfo info,
		final RenderPass pass,
		final CallbackInfo callback
	) {
		// Shared-pass world: opening a second RenderPass here throws. Keep vanilla draw.
		if (pass != null) {
			return;
		}
		// Legacy path (tests / future dedicated pass): moving-block first, then entity.
		if (MovingBlockVelocityRender.draw((PreparedRenderType)(Object)this, info) ||
			EntityVelocityRender.draw((PreparedRenderType)(Object)this, info)) {
			callback.cancel();
		}
	}
}
