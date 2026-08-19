package me.snowmii.dlss.mixin;

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
import me.snowmii.dlss.bridge.StreamlineFeatureMapping;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Collection;
import java.util.Set;

/**
 * Merge Streamline device extensions/features/extra queues immediately before vkCreateDevice.
 * Interposer would merge the same if Vulkan loaded through it; this claims the seam directly.
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
		// The feature names Streamline requires, mapped onto Minecraft's VulkanFeature records.
		// Names Minecraft already enables are skipped, so the set only grows by what the mod adds.
		vulkanFeatures.addAll(StreamlineFeatureMapping.requiredFeatures(
			Streamline.queryDeviceFeatures12(),
			Streamline.queryDeviceFeatures13()
		));
		// The wrapped createDevice declares BackendCreationException. Operation.call does not, so
		// the checked exception passes through undeclared - legal in bytecode, and the enclosing
		// createDevice still declares it, so the backend's own handling is unchanged.
		return original.call(deviceExtensions, physicalDevice, vulkanFeatures);
	}

	/**
	 * Queue-family create map is unmodifiable. Copy, add SL graphics/compute counts (host
	 * queues Streamline's own start after). Optical-flow reported, never added: no host
	 * family → DLSS-G interop. Receiver is the first wrap-operation parameter.
	 */
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
