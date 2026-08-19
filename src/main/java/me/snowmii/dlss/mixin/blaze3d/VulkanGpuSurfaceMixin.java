package me.snowmii.dlss.mixin.blaze3d;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import me.snowmii.dlss.client.ClientRuntime;
import me.snowmii.dlss.client.RuntimeControls;
import me.snowmii.dlss.readout.SessionReadout;
import me.snowmii.dlss.render.WorldPhase;
import me.snowmii.streamline.StreamlineVulkanProxies;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Struct;
import org.lwjgl.vulkan.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

/**
 * All {@code VulkanGpuSurface} intercepts in one mixin.
 *
 * Acquire/present spans: bracketing injections around {@code acquireNextTexture} and
 * {@code present} so the world phase can track when the surface is blocked on swapchain image
 * availability (FG hold-back stalls here, invisible in FPS).
 *
 * Swapchain image count: raises {@code minImageCount} to the DLSS-G back-buffer requirement
 * while FG is enabled; falls back to vanilla {@code max(3, caps.min)} otherwise.
 *
 * Vulkan proxy routing: wraps {@code vkCreateSwapchainKHR}, {@code vkGetSwapchainImagesKHR},
 * {@code vkAcquireNextImageKHR}, {@code vkQueuePresentKHR}, and {@code vkDestroySwapchainKHR}
 * so every call goes through Streamline's manual-hooking proxies. Streamline is initialized
 * with {@code eUseManualHooking}: no global intercept; the host must forward these calls or
 * {@code presentCommon()} never fires and FG reports {@code presented=0}. Wrappers forward to
 * the driver when the proxy is idle.
 */
@Mixin(VulkanGpuSurface.class)
public class VulkanGpuSurfaceMixin {
	@Inject(method = "acquireNextTexture()V", at = @At("HEAD"))
	private void mcDlssAcquireStart(final CallbackInfo ci) {
		final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		if (phase != null) phase.acquireStart();
	}

	@Inject(method = "acquireNextTexture()V", at = @At("RETURN"))
	private void mcDlssAcquireEnd(final CallbackInfo ci) {
		final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		if (phase != null) phase.acquireEnd();
	}

	@Inject(method = "present()V", at = @At("HEAD"))
	private void mcDlssPresentStart(final CallbackInfo ci) {
		final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		if (phase != null) phase.presentStart();
	}

	@Inject(method = "present()V", at = @At("RETURN"))
	private void mcDlssPresentEnd(final CallbackInfo ci) {
		final WorldPhase phase = ClientRuntime.active().activeWorldPhase();
		if (phase != null) phase.presentEnd();
	}


	@Inject(method = "configure(Lcom/mojang/blaze3d/systems/GpuSurface$Configuration;)V", at = @At("HEAD"))
	private void mcDlssReportConfiguration(final GpuSurface.Configuration config, final CallbackInfo ci) {
		final RuntimeControls controls = ClientRuntime.active().activeControls();
		SessionReadout.emit(
			"DLSS surface configure: " + config.width() + "x" + config.height()
				+ " present=" + config.presentMode()
				+ " fg=" + (controls != null && controls.getSurfacePolicy().getUserEnabled())
				+ " declaredBackBuffers=" + (controls == null ? 0 : controls.getSurfacePolicy().getRequiredSwapchainImages())
		);
	}

	@ModifyExpressionValue(
		method = "configure(Lcom/mojang/blaze3d/systems/GpuSurface$Configuration;)V",
		at = @At(
			value = "INVOKE",
			target = "Ljava/lang/Math;max(II)I"
		)
	)
	private int mcDlssFgMinImageCount(final int original) {
		final RuntimeControls controls = ClientRuntime.active().activeControls();
		if (controls == null) {
			return original;
		}
		return controls.getSurfacePolicy().minImageCount(original);
	}


	@WrapOperation(
		method = "configure(Lcom/mojang/blaze3d/systems/GpuSurface$Configuration;)V",
		at = @At(
			value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkCreateSwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"
		)
	)
	private int mcDlssCreateSwapchain(
		final VkDevice device,
		final VkSwapchainCreateInfoKHR pCreateInfo,
		final VkAllocationCallbacks pAllocator,
		final LongBuffer pSwapchain,
		final Operation<Integer> original
	) {
		if (!StreamlineVulkanProxies.available()) {
			return original.call(device, pCreateInfo, pAllocator, pSwapchain);
		}
		return StreamlineVulkanProxies.createSwapchain(
			device.address(), pCreateInfo.address(), address(pAllocator), address(pSwapchain)
		);
	}

	@WrapOperation(
		method = "configure(Lcom/mojang/blaze3d/systems/GpuSurface$Configuration;)V",
		at = @At(
			value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkGetSwapchainImagesKHR(Lorg/lwjgl/vulkan/VkDevice;JLjava/nio/IntBuffer;Ljava/nio/LongBuffer;)I"
		)
	)
	private int mcDlssGetSwapchainImages(
		final VkDevice device,
		final long swapchain,
		final IntBuffer pSwapchainImageCount,
		final LongBuffer pSwapchainImages,
		final Operation<Integer> original
	) {
		final int result = StreamlineVulkanProxies.available()
			? StreamlineVulkanProxies.getSwapchainImages(
			device.address(), swapchain, address(pSwapchainImageCount), address(pSwapchainImages)
		)
			: original.call(device, swapchain, pSwapchainImageCount, pSwapchainImages);
		// DIAGNOSTIC: the count query (images == null) answers how many images the driver actually
		// created, which is the number that decides whether the pacer can hold its generated frames
		// back or the next acquire blocks instead. The declared back-buffer count is only a request:
		// the surface capabilities can cap it, and nothing else reports what survived.
		if (pSwapchainImages == null && pSwapchainImageCount != null) {
			SessionReadout.emit("DLSS swapchain images: created=" + pSwapchainImageCount.get(0));
		}
		return result;
	}

	@WrapOperation(
		method = "acquireNextTexture()V",
		at = @At(
			value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkAcquireNextImageKHR(Lorg/lwjgl/vulkan/VkDevice;JJJJLjava/nio/IntBuffer;)I"
		)
	)
	private int mcDlssAcquireNextImage(
		final VkDevice device,
		final long swapchain,
		final long timeout,
		final long semaphore,
		final long fence,
		final IntBuffer pImageIndex,
		final Operation<Integer> original
	) {
		if (!StreamlineVulkanProxies.available()) {
			return original.call(device, swapchain, timeout, semaphore, fence, pImageIndex);
		}
		return StreamlineVulkanProxies.acquireNextImage(
			device.address(), swapchain, timeout, semaphore, fence, address(pImageIndex)
		);
	}

	@WrapOperation(
		method = "present()V",
		at = @At(
			value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"
		)
	)
	private int mcDlssQueuePresent(
		final VkQueue queue, final VkPresentInfoKHR pPresentInfo, final Operation<Integer> original
	) {
		if (!StreamlineVulkanProxies.available()) {
			return original.call(queue, pPresentInfo);
		}
		return StreamlineVulkanProxies.queuePresent(queue.address(), pPresentInfo.address());
	}

	@WrapOperation(
		method = "destroySwapchain()V",
		at = @At(
			value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkDestroySwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkAllocationCallbacks;)V"
		)
	)
	private void mcDlssDestroySwapchain(
		final VkDevice device,
		final long swapchain,
		final VkAllocationCallbacks pAllocator,
		final Operation<Void> original
	) {
		if (!StreamlineVulkanProxies.available()) {
			original.call(device, swapchain, pAllocator);
			return;
		}
		StreamlineVulkanProxies.destroySwapchain(device.address(), swapchain, address(pAllocator));
	}

	@Unique
	private static long address(final Struct<?> struct) {
		return struct == null ? 0L : struct.address();
	}

	@Unique
	private static long address(final IntBuffer buffer) {
		return buffer == null ? 0L : MemoryUtil.memAddress(buffer);
	}

	@Unique
	private static long address(final LongBuffer buffer) {
		return buffer == null ? 0L : MemoryUtil.memAddress(buffer);
	}

}
