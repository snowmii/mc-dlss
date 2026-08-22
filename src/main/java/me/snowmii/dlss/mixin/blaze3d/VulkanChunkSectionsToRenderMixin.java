package me.snowmii.dlss.mixin.blaze3d;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.render.WorldPhase;
import me.snowmii.dlss.render.mrt.TerrainVelocityPass;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Minecraft 26.3 opens the main solid/translucent {@link RenderPass} in
 * {@code LevelRenderer} before {@code ChunkSectionsToRender.renderGroup} runs, so the
 * velocity-companion sentinel clear must happen on that createRenderPass call — not inside
 * {@code renderGroup} while the pass is already open.
 */
@Mixin(LevelRenderer.class)
public class VulkanChunkSectionsToRenderMixin {
	@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
	@WrapOperation(
		method = "lambda$addMainPass$0",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/renderpearl/api/commands/RenderPass;"
		)
	)
	private RenderPass mcDlssClearTerrainVelocityCompanion(
		final CommandEncoder encoder,
		final Supplier<String> label,
		final GpuTextureView colorTexture,
		final Optional<Vector4fc> clearColor,
		final GpuTextureView depthTexture,
		final OptionalDouble clearDepth,
		final Operation<RenderPass> original
	) {
		final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		final GpuTextureView velocity = phase == null ? null : phase.getTerrainVelocityView();
		return TerrainVelocityPass.createPass(
			encoder,
			velocity,
			true,
			() -> original.call(encoder, label, colorTexture, clearColor, depthTexture, clearDepth)
		);
	}
}
