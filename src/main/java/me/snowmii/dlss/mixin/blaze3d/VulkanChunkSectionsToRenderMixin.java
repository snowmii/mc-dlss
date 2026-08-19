package me.snowmii.dlss.mixin.blaze3d;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.render.mrt.TerrainVelocityPass;
import me.snowmii.dlss.render.WorldPhase;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Terrain chunk passes stay vanilla one-attachment. This seam owns the velocity companion
 * clear: opaque group encoder-clears to the invalid sentinel (not a pass clear) before the
 * pass exists; translucent loads. Null velocity view → passthrough, never throws.
 */
@Mixin(ChunkSectionsToRender.class)
public class VulkanChunkSectionsToRenderMixin {
	@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
	@WrapOperation(
		method = "renderGroup",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;"
		)
	)
	private RenderPass mcDlssChunkRenderPass(
		final CommandEncoder encoder,
		final Supplier<String> label,
		final GpuTextureView colorTexture,
		final Optional<Vector4fc> clearColor,
		final GpuTextureView depthTexture,
		final OptionalDouble clearDepth,
		final Operation<RenderPass> original,
		// The enclosing renderGroup argument: the opaque group clears the companion before
		// any object writer draws; the translucent group loads it.
		@Local(argsOnly = true, name = "group") final ChunkSectionLayerGroup group
	) {
		return TerrainVelocityPass.createPass(
			encoder,
			mcDlssTerrainVelocityView(),
			group == ChunkSectionLayerGroup.OPAQUE,
			() -> original.call(encoder, label, colorTexture, clearColor, depthTexture, clearDepth)
		);
	}

	@Unique
	private static GpuTextureView mcDlssTerrainVelocityView() {
		final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		return phase == null ? null : phase.getTerrainVelocityView();
	}
}
