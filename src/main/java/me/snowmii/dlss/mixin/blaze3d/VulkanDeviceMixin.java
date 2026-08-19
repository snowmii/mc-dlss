package me.snowmii.dlss.mixin.blaze3d;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanInstance;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.checkpoints.CheckpointExtension;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.render.WorldPhase;
import me.snowmii.dlss.render.mrt.MotionVectorPipeline;
import me.snowmii.dlss.render.mrt.MotionVectorShader;
import me.snowmii.dlss.streamline.VulkanContextCapture;
import me.snowmii.streamline.Streamline;
import me.snowmii.streamline.VulkanContext;
import me.snowmii.streamline.VulkanContextRegistry;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Set;

/**
 * All {@code VulkanDevice} intercepts in one mixin.
 *
 * Context capture: injects at the constructor TAIL (after all fields are final) to extract
 * live Vulkan handles. {@code VulkanDevice} carries no physical-device field, so the physical
 * device is taken from the constructor args. The command-buffer source is stored but never
 * invoked at capture time: calling Minecraft's shared encoder outside a frame can disturb
 * command-buffer/submission state; recording happens later via
 * {@code VulkanContext.allocateRecordingCommandBuffer()}.
 *
 * Pipeline observation: injects at {@code getOrCompilePipeline} HEAD while the world phase is
 * open, so the session compatibility latch is established before a bind can expose an attachment
 * mismatch. Reload precompile and non-world rendering are outside that window and are ignored.
 */
@Mixin(VulkanDevice.class)
public class VulkanDeviceMixin {
	@Inject(at = @At("TAIL"), method = "<init>")
	private void mcDlssCaptureVulkanContext(
		ShaderSource defaultShaderSource,
		VulkanInstance instance,
		VulkanPhysicalDevice physicalDevice,
		Set<String> enabledDeviceExtensions,
		VkDevice vkDevice,
		long vma,
		CheckpointExtension checkpointExtension,
		CallbackInfo info
	) {
		VulkanContext context =
			VulkanContextCapture.capture((VulkanDevice) (Object) this, physicalDevice);
		if (context != null) {
			VulkanContextRegistry.register(context);
			Streamline.activateVulkanProxies(context);
		}
	}

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
