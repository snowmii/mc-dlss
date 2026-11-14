package me.snowmii.dlss.mixin.blaze3d;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import me.snowmii.dlss.render.StoragePresentationImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageFormatProperties;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(VulkanGpuTexture.class)
public abstract class VulkanGpuTextureMixin implements StoragePresentationImage {
    @Shadow @Final private VulkanDevice device;
    @Unique private boolean mcDlss$storageWritable;

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
        target = "Lorg/lwjgl/util/vma/Vma;vmaCreateImage(JLorg/lwjgl/vulkan/VkImageCreateInfo;Lorg/lwjgl/util/vma/VmaAllocationCreateInfo;Ljava/nio/LongBuffer;Lorg/lwjgl/PointerBuffer;Lorg/lwjgl/util/vma/VmaAllocationInfo;)I"), index = 1)
    private VkImageCreateInfo mcDlssStorageUsage(VkImageCreateInfo info) {
        if (info.format() != VK10.VK_FORMAT_R8G8B8A8_UNORM ||
            (info.usage() & VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) == 0 ||
            info.imageType() != VK10.VK_IMAGE_TYPE_2D || info.arrayLayers() != 1 ||
            info.mipLevels() != 1 || info.samples() != VK10.VK_SAMPLE_COUNT_1_BIT) return info;

        int usage = info.usage() | VK10.VK_IMAGE_USAGE_STORAGE_BIT;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageFormatProperties properties = VkImageFormatProperties.calloc(stack);
            int result = VK10.vkGetPhysicalDeviceImageFormatProperties(device.vkDevice().getPhysicalDevice(),
                info.format(), info.imageType(), info.tiling(), usage, info.flags(), properties);
            if (result == VK10.VK_SUCCESS && info.extent().width() <= properties.maxExtent().width() &&
                info.extent().height() <= properties.maxExtent().height()) {
                info.usage(usage);
                mcDlss$storageWritable = true;
            }
        }
        return info;
    }

    @Override
    public boolean mcDlssStorageWritable() {
        return mcDlss$storageWritable;
    }
}
