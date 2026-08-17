package me.snowmii.dlss.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.mrt.MotionVectorPipeline;
import me.snowmii.dlss.mrt.MotionVectorShader;
import me.snowmii.dlss.render.WorldPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Observes every pipeline entering Vulkan's lazy compile/cache seam while the DLSS world phase is
 * open. Shader reload precompilation and non-world rendering happen outside that phase and are
 * deliberately ignored.
 *
 * Observation runs at HEAD, before compilation and binding can expose an attachment mismatch.
 * This observer establishes the session compatibility latch; velocity variants consume that
 * route when choosing the pipeline and world-pass attachment shape.
 */
@Mixin(VulkanDevice.class)
public class VulkanPipelineCompatibilityMixin {
	@Inject(at = @At("HEAD"), method = "getOrCompilePipeline")
	private void mcDlssObserveWorldPipeline(
		RenderPipeline pipeline,
		CallbackInfoReturnable<?> info
	) {
		WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		if (phase == null) {
			return;
		}

		phase.observePipeline(new MotionVectorPipeline(
			pipeline.getLocation().toString(),
			List.of(
				new MotionVectorShader(
					pipeline.getVertexShader().toString(),
					pipeline.getVertexShader().getNamespace()
				),
				new MotionVectorShader(
					pipeline.getFragmentShader().toString(),
					pipeline.getFragmentShader().getNamespace()
				)
			)
		));
	}
}
