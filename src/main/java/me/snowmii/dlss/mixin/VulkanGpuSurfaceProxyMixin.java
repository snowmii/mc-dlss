package me.snowmii.dlss.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import me.snowmii.streamline.StreamlineVulkanProxies;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Struct;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Routes Minecraft's Vulkan present chain through Streamline's interposer wrappers.
 *
 * Streamline is initialized with {@code eUseManualHooking}: no global intercept, host must
 * call the proxies. Minecraft otherwise hits LWJGL's driver-resolved functions; SL never sees
 * them, {@code presentCommon()} never runs, and FG reports {@code presented=0}.
 *
 * Wrap the call, do not overwrite the method. Routing is unconditional: manual-hooking
 * requires {@code presentCommon()} every frame; wrappers forward to the driver when idle.
 */
@Mixin(VulkanGpuSurface.class)
public class VulkanGpuSurfaceProxyMixin {
	@Unique
	private static final Logger LOGGER = LoggerFactory.getLogger("mc-dlss");

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
			LOGGER.info("DLSS swapchain images: created={}", pSwapchainImageCount.get(0));
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

	/** Vulkan null pointer when the optional is absent. */
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
