package me.snowmii.dlss.mixin;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.IntIntPair;
import me.snowmii.streamline.VulkanContext;
import org.lwjgl.vulkan.VK12;

/**
 * Mod-side capture factory: builds the SDK's {@link VulkanContext} from live Minecraft
 * {@link VulkanDevice} / {@link VulkanPhysicalDevice} internals, offloading the engine-touching
 * part of the capture out of the engine-free SDK.
 *
 * <p>Not a mixin: a plain static utility invoked from the ctor-TAIL injection in
 * {@code VulkanDeviceContextMixin}. Relocated verbatim from the old engine-coupled
 * {@code fromVulkanDevice} factory; same null/zero-handle degradation semantics.
 *
 * <p>{@link VulkanDevice} exposes no physical-device accessor, so the mixin passes the
 * constructor argument straight through. NGX initialization needs it.
 */
public final class VulkanContextCapture {
	private VulkanContextCapture() {
	}

	/**
	 * Production seam: capture Minecraft's live Vulkan context from a constructed
	 * {@link VulkanDevice} (called at ctor TAIL, all fields final). Returns null if any
	 * handle is zero, so the mod degrades gracefully.
	 */
	public static VulkanContext capture(final VulkanDevice device, final VulkanPhysicalDevice physicalDevice) {
		final long instanceHandle = device.instance().vkInstance().address();
		final long physicalDeviceHandle = physicalDevice.vkPhysicalDevice().address();
		final long deviceHandle = device.vkDevice().address();
		final long queueHandle = device.graphicsQueue().vkQueue().address();
		// Minecraft records no graphics queue when no family can present, which is exactly the
		// case that must not reach Streamline's Vulkan info - degrade like the zero handles do.
		final IntIntPair graphicsPair = physicalDevice.graphicsQueueFamilyAndIndex();
		if (graphicsPair == null) {
			return null;
		}
		if (instanceHandle == 0L || physicalDeviceHandle == 0L || deviceHandle == 0L || queueHandle == 0L) {
			return null;
		}
		final VulkanCommandEncoder encoder = device.createCommandEncoder();
		final Int2IntMap queueFamilyMap = physicalDevice.queueFamilyCreateInfoMap();
		final IntIntPair computePair = physicalDevice.computeQueueFamilyAndIndex();
		return VulkanContext.fromNativeHandles(
			instanceHandle,
			physicalDeviceHandle,
			deviceHandle,
			queueHandle,
			graphicsPair.leftInt(),
			// Streamline's graphicsQueueIndex is where its own queues start, which is after the
			// queues Minecraft created in the family - the host queue COUNT from the create-info
			// map, not the pair's queue index.
			queueFamilyMap.get(graphicsPair.leftInt()),
			// Same semantics for compute: the count of host queues in the compute family, and
			// 0/0 when Minecraft found no compute family (SL then cannot be told one).
			computePair == null ? 0 : computePair.leftInt(),
			computePair == null ? 0 : queueFamilyMap.get(computePair.leftInt()),
			encoder::allocateAndBeginTransientCommandBuffer,
			// execute() ends whatever the encoder was recording and appends this buffer behind
			// it, so the buffer has to be closed here first. It is not submitted by this call;
			// the encoder's next submit carries it with the rest of the frame.
			commandBuffer -> {
				VK12.vkEndCommandBuffer(commandBuffer);
				encoder.execute(commandBuffer);
			}
		);
	}
}