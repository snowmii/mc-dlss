package me.snowmii.dlss.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import me.snowmii.dlss.bridge.StreamlineVulkanProxies;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Struct;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Routes Minecraft's Vulkan present chain through Streamline's interposer wrappers.
 *
 * <p>Streamline is initialized with {@code eUseManualHooking}, so it installs no global
 * interception: the host must call the proxies for the mandatory present-chain entry points
 * itself. Minecraft calls LWJGL's driver-resolved functions, which SL never sees - so DLSS-G's
 * {@code slHookVkCreateSwapchainKHR} never fires, the common plugin's {@code presentCommon()}
 * never runs, SL's frame index never leaves 1, and frame generation reports
 * {@code presented=0 status=0 fence=0} no matter how correct the tagging, constants, options,
 * and markers around it are.
 *
 * <p>Each call is wrapped rather than the enclosing method overwritten, so Minecraft keeps
 * ownership of the whole swapchain lifecycle and only the dispatch target changes. When the
 * staged runtime is absent the proxies are unavailable and every wrapper calls the original
 * operation, leaving vanilla behaviour exactly intact - which is also what makes these
 * chainable with another mod wrapping the same call sites.
 *
 * <p>Routing is unconditional rather than gated on frame generation: the manual-hooking contract
 * requires {@code presentCommon()} every frame, and the wrappers forward to the driver when no
 * feature is active.
 */
@Mixin(VulkanGpuSurface.class)
public class VulkanGpuSurfaceProxyMixin {
	@WrapOperation(
		method = "configure(Lcom/mojang/blaze3d/systems/GpuSurface$Configuration;)V",
		at = @At(
			value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkCreateSwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"
		)
	)
	private int mcDlssCreateSwapchain(
		final VkDevice device,
		final VkSwapchainCreateInfoKHR createInfo,
		final VkAllocationCallbacks allocator,
		final LongBuffer swapchain,
		final Operation<Integer> original
	) {
		if (!StreamlineVulkanProxies.available()) {
			return original.call(device, createInfo, allocator, swapchain);
		}
		return StreamlineVulkanProxies.createSwapchain(
			device.address(), createInfo.address(), address(allocator), address(swapchain)
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
		final IntBuffer imageCount,
		final LongBuffer images,
		final Operation<Integer> original
	) {
		if (!StreamlineVulkanProxies.available()) {
			return original.call(device, swapchain, imageCount, images);
		}
		return StreamlineVulkanProxies.getSwapchainImages(
			device.address(), swapchain, address(imageCount), address(images)
		);
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
		final IntBuffer imageIndex,
		final Operation<Integer> original
	) {
		if (!StreamlineVulkanProxies.available()) {
			return original.call(device, swapchain, timeout, semaphore, fence, imageIndex);
		}
		return StreamlineVulkanProxies.acquireNextImage(
			device.address(), swapchain, timeout, semaphore, fence, address(imageIndex)
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
		final VkQueue queue, final VkPresentInfoKHR presentInfo, final Operation<Integer> original
	) {
		if (!StreamlineVulkanProxies.available()) {
			return original.call(queue, presentInfo);
		}
		return StreamlineVulkanProxies.queuePresent(queue.address(), presentInfo.address());
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
		final VkAllocationCallbacks allocator,
		final Operation<Void> original
	) {
		if (!StreamlineVulkanProxies.available()) {
			original.call(device, swapchain, allocator);
			return;
		}
		StreamlineVulkanProxies.destroySwapchain(device.address(), swapchain, address(allocator));
	}

	/** Vulkan's null pointer for an absent optional struct, its address otherwise. */
	private static long address(final Struct<?> struct) {
		return struct == null ? 0L : struct.address();
	}

	/** Vulkan's null pointer for an absent optional out-buffer, its address otherwise. */
	private static long address(final IntBuffer buffer) {
		return buffer == null ? 0L : MemoryUtil.memAddress(buffer);
	}

	/** Vulkan's null pointer for an absent optional out-buffer, its address otherwise. */
	private static long address(final LongBuffer buffer) {
		return buffer == null ? 0L : MemoryUtil.memAddress(buffer);
	}
}
