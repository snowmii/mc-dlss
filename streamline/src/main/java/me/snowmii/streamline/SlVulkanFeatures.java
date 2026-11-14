package me.snowmii.streamline;

import java.util.List;

/**
 * Streamline's Vulkan feature-bit table as plain engine-free data.
 *
 * <p>The name tables mirror the complete {@code SL_VK_FEATURE} lists in Streamline 2.14.1's
 * {@code sl_helpers_vk.h} ({@code getVkPhysicalDeviceVulkan12Features} and
 * {@code getVkPhysicalDeviceVulkan13Features}); each name maps to the offset of the matching
 * {@code VkBool32} member inside its Vulkan struct, which is what Streamline's
 * {@code slGetFeatureRequirements} semantics expect the host to merge into the enabled-feature
 * set. No engine knowledge lives here: the consumer maps each requirement onto its own
 * renderer's feature records.
 *
 * <p>The offsets and struct sizes below are the values of the corresponding
 * {@code lwjgl-vulkan-3.4.1} constants, extracted by running that jar's classes: the structs
 * compute them in their static initializer from their member layout, they are not compile-time
 * literals, so referencing the LWJGL constants here would initialize {@code org.lwjgl.vulkan}
 * classes when this table loads - before the Streamline runtime is staged, which the SDK's
 * load ordering forbids. Every value is therefore hardcoded with the LWJGL constant it came
 * from named beside it.
 */
public final class SlVulkanFeatures {
	/**
	 * One feature-bit requirement: the Vulkan structure the bit lives in (its sType and struct
	 * size) and the byte offset of the feature's {@code VkBool32} member inside it.
	 */
	public record FeatureRequirement(int sType, int structSize, String name, long offset) {
	}

	private static final int VULKAN_12_S_TYPE = 51;
	private static final int VULKAN_13_S_TYPE = 1000088000;

	private static final int VULKAN_12_STRUCT_SIZE = 208;
	private static final int VULKAN_13_STRUCT_SIZE = 80;

	private static final List<FeatureRequirement> REQUIREMENTS = List.of(
		feat12("samplerMirrorClampToEdge", 16),
		feat12("drawIndirectCount", 20),
		feat12("storageBuffer8BitAccess", 24),
		feat12("uniformAndStorageBuffer8BitAccess", 28),
		feat12("storagePushConstant8", 32),
		feat12("shaderBufferInt64Atomics", 36),
		feat12("shaderSharedInt64Atomics", 40),
		feat12("shaderFloat16", 44),
		feat12("shaderInt8", 48),
		feat12("descriptorIndexing", 52),
		feat12("shaderInputAttachmentArrayDynamicIndexing", 56),
		feat12("shaderUniformTexelBufferArrayDynamicIndexing", 60),
		feat12("shaderStorageTexelBufferArrayDynamicIndexing", 64),
		feat12("shaderUniformBufferArrayNonUniformIndexing", 68),
		feat12("shaderSampledImageArrayNonUniformIndexing", 72),
		feat12("shaderStorageBufferArrayNonUniformIndexing", 76),
		feat12("shaderStorageImageArrayNonUniformIndexing", 80),
		feat12("shaderInputAttachmentArrayNonUniformIndexing", 84),
		feat12("shaderUniformTexelBufferArrayNonUniformIndexing", 88),
		feat12("shaderStorageTexelBufferArrayNonUniformIndexing", 92),
		feat12("descriptorBindingUniformBufferUpdateAfterBind", 96),
		feat12("descriptorBindingSampledImageUpdateAfterBind", 100),
		feat12("descriptorBindingStorageImageUpdateAfterBind", 104),
		feat12("descriptorBindingStorageBufferUpdateAfterBind", 108),
		feat12("descriptorBindingUniformTexelBufferUpdateAfterBind", 112),
		feat12("descriptorBindingStorageTexelBufferUpdateAfterBind", 116),
		feat12("descriptorBindingUpdateUnusedWhilePending", 120),
		feat12("descriptorBindingPartiallyBound", 124),
		feat12("descriptorBindingVariableDescriptorCount", 128),
		feat12("runtimeDescriptorArray", 132),
		feat12("samplerFilterMinmax", 136),
		feat12("scalarBlockLayout", 140),
		feat12("imagelessFramebuffer", 144),
		feat12("uniformBufferStandardLayout", 148),
		feat12("shaderSubgroupExtendedTypes", 152),
		feat12("separateDepthStencilLayouts", 156),
		feat12("hostQueryReset", 160),
		feat12("timelineSemaphore", 164),
		feat12("bufferDeviceAddress", 168),
		feat12("bufferDeviceAddressCaptureReplay", 172),
		feat12("bufferDeviceAddressMultiDevice", 176),
		feat12("vulkanMemoryModel", 180),
		feat12("vulkanMemoryModelDeviceScope", 184),
		feat12("vulkanMemoryModelAvailabilityVisibilityChains", 188),
		feat12("shaderOutputViewportIndex", 192),
		feat12("shaderOutputLayer", 196),
		feat12("subgroupBroadcastDynamicId", 200),

		feat13("robustImageAccess", 16),
		feat13("inlineUniformBlock", 20),
		feat13("descriptorBindingInlineUniformBlockUpdateAfterBind", 24),
		feat13("pipelineCreationCacheControl", 28),
		feat13("privateData", 32),
		feat13("shaderDemoteToHelperInvocation", 36),
		feat13("shaderTerminateInvocation", 40),
		feat13("subgroupSizeControl", 44),
		feat13("computeFullSubgroups", 48),
		feat13("synchronization2", 52),
		feat13("textureCompressionASTC_HDR", 56),
		feat13("shaderZeroInitializeWorkgroupMemory", 60),
		feat13("dynamicRendering", 64),
		feat13("shaderIntegerDotProduct", 68),
		feat13("maintenance4", 72)
	);

	private SlVulkanFeatures() {
	}

	/**
	 * The name→(sType, structSize, offset) table in header order: the
	 * {@code getVkPhysicalDeviceVulkan12Features} entries first, then the
	 * {@code getVkPhysicalDeviceVulkan13Features} entries. Unmodifiable.
	 */
	public static List<FeatureRequirement> requirements() {
		return REQUIREMENTS;
	}

	private static FeatureRequirement feat12(final String name, final long offset) {
		return new FeatureRequirement(VULKAN_12_S_TYPE, VULKAN_12_STRUCT_SIZE, name, offset);
	}

	private static FeatureRequirement feat13(final String name, final long offset) {
		return new FeatureRequirement(VULKAN_13_S_TYPE, VULKAN_13_STRUCT_SIZE, name, offset);
	}
}