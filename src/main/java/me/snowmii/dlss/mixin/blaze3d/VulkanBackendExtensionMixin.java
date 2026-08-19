package me.snowmii.dlss.mixin.blaze3d;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.IntIntPair;
import me.snowmii.streamline.Streamline;
import me.snowmii.streamline.SlQueueRequirements;
import me.snowmii.dlss.streamline.StreamlineFeatureMapping;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Collection;
import java.util.Set;

/**
 * All {@code VulkanBackend} intercepts for Streamline device initialization in one mixin.
 *
 * Extensions and features: wraps the inner {@code createDevice} overload to append Streamline's
 * required device extensions and map its Vulkan 1.2/1.3 feature names onto Minecraft's
 * {@code VulkanFeature} records, immediately before {@code vkCreateDevice}. An interposer would
 * merge the same if Vulkan loaded through it; this claims the seam directly.
 *
 * Queue families: wraps {@code queueFamilyCreateInfoMap()} to copy the unmodifiable map and add
 * Streamline's required graphics and compute queue counts. Optical-flow queues are reported by
 * Streamline but not added here: there is no host queue family for them, so DLSS-G interop
 * handles them separately.
 */
@Mixin(VulkanBackend.class)
public abstract class VulkanBackendExtensionMixin {
	@WrapOperation(
		method = "createDevice(JLcom/mojang/blaze3d/shaders/ShaderSource;Lcom/mojang/blaze3d/shaders/GpuDebugOptions;Ljava/lang/Runnable;)Lcom/mojang/blaze3d/systems/GpuDevice;",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vulkan/VulkanBackend;createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;"
		)
	)
	private VkDevice mcDlssCreateDeviceWithExtensions(
		Collection<String> deviceExtensions,
		VulkanPhysicalDevice physicalDevice,
		Set<VulkanFeature> vulkanFeatures,
		Operation<VkDevice> original
	) {
		VkPhysicalDevice vkPhysicalDevice = physicalDevice.vkPhysicalDevice();
		Streamline.addDeviceExtensions(
			deviceExtensions,
			vkPhysicalDevice.getInstance().address(),
			vkPhysicalDevice.address()
		);
		vulkanFeatures.addAll(StreamlineFeatureMapping.requiredFeatures(
			Streamline.queryDeviceFeatures12(),
			Streamline.queryDeviceFeatures13()
		));
		return original.call(deviceExtensions, physicalDevice, vulkanFeatures);
	}

	@WrapOperation(
		method = "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;queueFamilyCreateInfoMap()Lit/unimi/dsi/fastutil/ints/Int2IntMap;"
		)
	)
	private static Int2IntMap mcDlssMergedQueueMap(
		VulkanPhysicalDevice physicalDevice,
		Operation<Int2IntMap> original
	) {
		Int2IntMap merged = new Int2IntArrayMap(original.call(physicalDevice));
		SlQueueRequirements requirements = Streamline.queryQueueRequirements();
		IntIntPair graphics = physicalDevice.graphicsQueueFamilyAndIndex();
		if (graphics != null) {
			merged.put(graphics.leftInt(), merged.get(graphics.leftInt()) + requirements.graphicsQueues());
		}
		IntIntPair compute = physicalDevice.computeQueueFamilyAndIndex();
		if (compute != null) {
			merged.put(compute.leftInt(), merged.get(compute.leftInt()) + requirements.computeQueues());
		}
		return merged;
	}
}
