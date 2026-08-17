package me.snowmii.dlss.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.mrt.TerrainVelocityPass;
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
 * Keeps the terrain chunk passes' velocity-companion clear lifecycle on the velocity-MRT
 * route while leaving every pass shape and pipeline selection exactly vanilla.
 *
 * The terrain camera-motion writers are retired: {@code ChunkSectionsToRender.renderGroup} -
 * the one method that draws both terrain groups, OPAQUE (solid and cutout layers) and
 * TRANSLUCENT - no longer adds the RG16_FLOAT velocity attachment at color index 1 and no
 * longer swaps bound pipelines for velocity twins. The pass is created exactly as vanilla
 * creates it (one source attachment, source depth, full render area) and the source pipeline
 * binds unchanged, on every route.
 *
 * What the terrain seam still owns is the companion's clear lifecycle, delegated to
 * {@link TerrainVelocityPass}: while the DLSS world phase is open on the velocity-MRT route
 * and the scene target holds a velocity companion, the pass-creation redirect clears the
 * companion to the invalid sentinel before the opaque group's pass exists (an encoder
 * command, never a pass clear), so every pixel no retained object writer covers reads
 * invalid rather than stale motion from an earlier frame. The translucent group loads the
 * companion instead of clearing it, preserving what the opaque group's clear and the object
 * writers left. Outside the eligible phase or on the latched camera-only route the velocity
 * view is null and the handler calls the operation through without clearing, so nothing
 * this mixin does can throw.
 *
 * The session's pipeline-compatibility latch is still observed at Vulkan's lazy-compile seam
 * ({@code VulkanPipelineCompatibilityMixin}): a first-encounter foreign pipeline latches the
 * camera-only route at its first bind, after which the velocity view reads null and every
 * pass and writer falls back to vanilla.
 *
 * Chaining is preserved: the handler calls the wrapped operation through with the original
 * arguments, so another mod's wrap around this call site still runs. TerrainVelocityPass
 * only owns the conditional clear, never pass creation.
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
