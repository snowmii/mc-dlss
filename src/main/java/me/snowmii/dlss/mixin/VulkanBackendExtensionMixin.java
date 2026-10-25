package me.snowmii.dlss.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.IntIntPair;
import me.snowmii.dlss.vk.DeviceExtensionRules;
import me.snowmii.dlss.vk.DeviceQueueRules;
import me.snowmii.dlss.vk.VulkanFeatureContributions;
import me.snowmii.dlss.streamline.VulkanBootstrap;
import me.snowmii.streamline.Streamline;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.HashSet;
import java.util.Set;

/** Streamline's extensions depend on the selected GPU and live Vulkan instance. */
@Mixin(VulkanBackend.class)
public abstract class VulkanBackendExtensionMixin {
    @WrapOperation(
        method = "createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanBackend;createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;"
        )
    )
    private static VkDevice dlssCreateDeviceWithExtensions(
        final FeatureSet featureSet,
        final VulkanPhysicalDevice physicalDevice,
        final Operation<VkDevice> original
    ) {
        final VkPhysicalDevice vkPhysicalDevice = physicalDevice.vkPhysicalDevice();
        final Set<String> extraExtensions = new HashSet<>();
        Streamline.addDeviceExtensions(
            extraExtensions, vkPhysicalDevice.getInstance().address(), vkPhysicalDevice.address()
        );
        final FeatureSet merged = featureSet.composite(new FeatureSet(
            "mc-dlss", extraExtensions, VulkanFeatureContributions.all()
        ));
        return original.call(DeviceExtensionRules.normalize(merged), physicalDevice);
    }

	/** Streamline needs extra graphics/compute queues; Minecraft only creates its own. */
	@WrapOperation(
		method = "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;queueFamilyCreateInfoMap()Lit/unimi/dsi/fastutil/ints/Int2IntMap;"
		)
	)
	private static Int2IntMap dlssMergedQueueMap(
		final VulkanPhysicalDevice physicalDevice,
		final Operation<Int2IntMap> original
	) {
		final IntIntPair graphics = physicalDevice.graphicsQueueFamilyAndIndex();
		final IntIntPair compute = physicalDevice.computeQueueFamilyAndIndex();
		final Int2IntMap host = original.call(physicalDevice);
		if (graphics == null) return host;
		return DeviceQueueRules.reserve(host, graphics.leftInt(), compute == null ? -1 : compute.leftInt(),
			VulkanBootstrap.getQueueRequirements()).counts();
	}
}
