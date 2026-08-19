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
 * Observe pipelines at Vulkan's lazy compile/cache HEAD while the world phase is open.
 * Reload precompile and non-world rendering are outside that phase. Establishes the
 * session compatibility latch before bind can expose an attachment mismatch.
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
