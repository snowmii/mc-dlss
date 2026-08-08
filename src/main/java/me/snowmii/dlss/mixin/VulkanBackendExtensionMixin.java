package me.snowmii.dlss.mixin;

import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.IntIntPair;
import me.snowmii.dlss.bridge.ExtensionBootstrap;
import me.snowmii.dlss.bridge.SlQueueRequirements;
import me.snowmii.dlss.bridge.SlVulkanFeatures;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collection;
import java.util.Set;

/**
 * Merges Streamline's device requirements into Minecraft 26.2's device creation, immediately
 * before vkCreateDevice: the SL-required device extensions and Vulkan 1.2/1.3 features join
 * the enabled sets, and the SL-required extra graphics/compute queues join the queue-family
 * create map. The interposer's own vkCreateDevice proxy merges the same requirements when the
 * client loads Vulkan through it, so this merge is the mod claiming the seam directly - the
 * device is correct even if that proxy path ever changes.
 */
@Mixin(VulkanBackend.class)
public abstract class VulkanBackendExtensionMixin {
	@Shadow
	private static VkDevice createDevice(
		Collection<String> extensions,
		VulkanPhysicalDevice physicalDevice,
		Set<VulkanFeature> features
	) throws BackendCreationException {
		throw new AssertionError();
	}

	@Redirect(
		method = "createDevice(JLcom/mojang/blaze3d/shaders/ShaderSource;Lcom/mojang/blaze3d/shaders/GpuDebugOptions;Ljava/lang/Runnable;)Lcom/mojang/blaze3d/systems/GpuDevice;",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vulkan/VulkanBackend;createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;"
		)
	)
	private VkDevice mcDlssCreateDeviceWithExtensions(
		Collection<String> extensions,
		VulkanPhysicalDevice physicalDevice,
		Set<VulkanFeature> features
	) throws BackendCreationException {
		VkPhysicalDevice vkPhysicalDevice = physicalDevice.vkPhysicalDevice();
		ExtensionBootstrap.addDeviceExtensions(
			extensions,
			vkPhysicalDevice.getInstance().address(),
			vkPhysicalDevice.address()
		);
		// The feature names Streamline requires, mapped onto Minecraft's VulkanFeature records.
		// Names Minecraft already enables are skipped, so the set only grows by what the mod adds.
		features.addAll(SlVulkanFeatures.slRequiredFeatures(
			ExtensionBootstrap.queryDeviceFeatures12(),
			ExtensionBootstrap.queryDeviceFeatures13()
		));
		return createDevice(extensions, physicalDevice, features);
	}

	/**
	 * The queue-family create map createDevice iterates is unmodifiable, so Streamline's extra
	 * queues cannot be merged in place. Copy it and add the SL counts to the graphics and compute
	 * families - the count of queues the host must create in each, so Streamline's own queues
	 * start right after them. Optical-flow queues are reported but never added: without a native
	 * optical-flow family, DLSS-G runs in interop mode.
	 *
	 * The handler's first parameter is the target instance: @Redirect prepends it for instance
	 * method targets, and calling queueFamilyCreateInfoMap() here reaches the original method
	 * (redirects only apply inside the redirected method).
	 */
	@Redirect(
		method = "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;queueFamilyCreateInfoMap()Lit/unimi/dsi/fastutil/ints/Int2IntMap;"
		)
	)
	private static Int2IntMap mcDlssMergedQueueMap(VulkanPhysicalDevice physicalDevice) {
		Int2IntMap original = physicalDevice.queueFamilyCreateInfoMap();
		Int2IntMap merged = new Int2IntArrayMap(original);
		SlQueueRequirements requirements = ExtensionBootstrap.queryQueueRequirements();
		IntIntPair graphics = physicalDevice.graphicsQueueFamilyAndIndex();
		if (graphics != null) {
			merged.put(graphics.leftInt(), merged.get(graphics.leftInt()) + requirements.getGraphicsQueues());
		}
		IntIntPair compute = physicalDevice.computeQueueFamilyAndIndex();
		if (compute != null) {
			merged.put(compute.leftInt(), merged.get(compute.leftInt()) + requirements.getComputeQueues());
		}
		return merged;
	}
}
