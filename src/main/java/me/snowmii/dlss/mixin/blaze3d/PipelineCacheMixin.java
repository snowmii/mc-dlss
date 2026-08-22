package me.snowmii.dlss.mixin.blaze3d;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.render.WorldPhase;
import me.snowmii.dlss.render.mrt.MotionVectorPipeline;
import me.snowmii.dlss.render.mrt.MotionVectorShader;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pipeline observation while the world phase is open, so the session compatibility latch is
 * established before a bind can expose an attachment mismatch. Reload precompile and non-world
 * rendering are outside that window and are ignored.
 */
@Mixin(PipelineCache.class)
public class PipelineCacheMixin {
	@Inject(at = @At("HEAD"), method = "get")
	private void mcDlssObserveWorldPipeline(
		RenderPipeline pipeline,
		CallbackInfoReturnable<?> info
	) {
		WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		if (phase == null) {
			return;
		}

		Map<ShaderType, Identifier> shaders = pipeline.getShaders();
		List<MotionVectorShader> observed = new ArrayList<>(2);
		Identifier vertex = shaders.get(ShaderType.VERTEX);
		Identifier fragment = shaders.get(ShaderType.FRAGMENT);
		if (vertex != null) {
			observed.add(new MotionVectorShader(vertex.toString(), vertex.getNamespace()));
		}
		if (fragment != null) {
			observed.add(new MotionVectorShader(fragment.toString(), fragment.getNamespace()));
		}
		phase.observePipeline(new MotionVectorPipeline(pipeline.getLocation().toString(), observed));
	}
}
