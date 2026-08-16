package me.snowmii.streamline;

import java.util.List;

/**
 * Streamline's Vulkan feature-bit table as plain engine-free data.
 *
 * <p>The name tables mirror the complete {@code SL_VK_FEATURE} lists in Streamline 2.12.0's
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

	/** sType 51 is {@code VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES}. */
	private static final int VULKAN_12_S_TYPE = 51;
	/** sType 1000088000 is {@code VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES}. */
	private static final int VULKAN_13_S_TYPE = 1000088000;

	/** {@code VkPhysicalDeviceVulkan12Features.SIZEOF}. */
	private static final int VULKAN_12_STRUCT_SIZE = 208;
	/** {@code VkPhysicalDeviceVulkan13Features.SIZEOF}. */
	private static final int VULKAN_13_STRUCT_SIZE = 80;

	private static final List<FeatureRequirement> REQUIREMENTS = List.of(
		// getVkPhysicalDeviceVulkan12Features, in header order.
		feat12("samplerMirrorClampToEdge", 16 /* VkPhysicalDeviceVulkan12Features.SAMPLERMIRRORCLAMPTOEDGE */),
		feat12("drawIndirectCount", 20 /* VkPhysicalDeviceVulkan12Features.DRAWINDIRECTCOUNT */),
		feat12("storageBuffer8BitAccess", 24 /* VkPhysicalDeviceVulkan12Features.STORAGEBUFFER8BITACCESS */),
		feat12("uniformAndStorageBuffer8BitAccess", 28 /* VkPhysicalDeviceVulkan12Features.UNIFORMANDSTORAGEBUFFER8BITACCESS */),
		feat12("storagePushConstant8", 32 /* VkPhysicalDeviceVulkan12Features.STORAGEPUSHCONSTANT8 */),
		feat12("shaderBufferInt64Atomics", 36 /* VkPhysicalDeviceVulkan12Features.SHADERBUFFERINT64ATOMICS */),
		feat12("shaderSharedInt64Atomics", 40 /* VkPhysicalDeviceVulkan12Features.SHADERSHAREDINT64ATOMICS */),
		feat12("shaderFloat16", 44 /* VkPhysicalDeviceVulkan12Features.SHADERFLOAT16 */),
		feat12("shaderInt8", 48 /* VkPhysicalDeviceVulkan12Features.SHADERINT8 */),
		feat12("descriptorIndexing", 52 /* VkPhysicalDeviceVulkan12Features.DESCRIPTORINDEXING */),
		feat12("shaderInputAttachmentArrayDynamicIndexing", 56 /* VkPhysicalDeviceVulkan12Features.SHADERINPUTATTACHMENTARRAYDYNAMICINDEXING */),
		feat12("shaderUniformTexelBufferArrayDynamicIndexing", 60 /* VkPhysicalDeviceVulkan12Features.SHADERUNIFORMTEXELBUFFERARRAYDYNAMICINDEXING */),
		feat12("shaderStorageTexelBufferArrayDynamicIndexing", 64 /* VkPhysicalDeviceVulkan12Features.SHADERSTORAGETEXELBUFFERARRAYDYNAMICINDEXING */),
		feat12("shaderUniformBufferArrayNonUniformIndexing", 68 /* VkPhysicalDeviceVulkan12Features.SHADERUNIFORMBUFFERARRAYNONUNIFORMINDEXING */),
		feat12("shaderSampledImageArrayNonUniformIndexing", 72 /* VkPhysicalDeviceVulkan12Features.SHADERSAMPLEDIMAGEARRAYNONUNIFORMINDEXING */),
		feat12("shaderStorageBufferArrayNonUniformIndexing", 76 /* VkPhysicalDeviceVulkan12Features.SHADERSTORAGEBUFFERARRAYNONUNIFORMINDEXING */),
		feat12("shaderStorageImageArrayNonUniformIndexing", 80 /* VkPhysicalDeviceVulkan12Features.SHADERSTORAGEIMAGEARRAYNONUNIFORMINDEXING */),
		feat12("shaderInputAttachmentArrayNonUniformIndexing", 84 /* VkPhysicalDeviceVulkan12Features.SHADERINPUTATTACHMENTARRAYNONUNIFORMINDEXING */),
		feat12("shaderUniformTexelBufferArrayNonUniformIndexing", 88 /* VkPhysicalDeviceVulkan12Features.SHADERUNIFORMTEXELBUFFERARRAYNONUNIFORMINDEXING */),
		feat12("shaderStorageTexelBufferArrayNonUniformIndexing", 92 /* VkPhysicalDeviceVulkan12Features.SHADERSTORAGETEXELBUFFERARRAYNONUNIFORMINDEXING */),
		feat12("descriptorBindingUniformBufferUpdateAfterBind", 96 /* VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGUNIFORMBUFFERUPDATEAFTERBIND */),
		feat12("descriptorBindingSampledImageUpdateAfterBind", 100 /* VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGSAMPLEDIMAGEUPDATEAFTERBIND */),
		feat12("descriptorBindingStorageImageUpdateAfterBind", 104 /* VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGSTORAGEIMAGEUPDATEAFTERBIND */),
		feat12("descriptorBindingStorageBufferUpdateAfterBind", 108 /* VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGSTORAGEBUFFERUPDATEAFTERBIND */),
		feat12("descriptorBindingUniformTexelBufferUpdateAfterBind", 112 /* VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGUNIFORMTEXELBUFFERUPDATEAFTERBIND */),
		feat12("descriptorBindingStorageTexelBufferUpdateAfterBind", 116 /* VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGSTORAGETEXELBUFFERUPDATEAFTERBIND */),
		feat12("descriptorBindingUpdateUnusedWhilePending", 120 /* VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGUPDATEUNUSEDWHILEPENDING */),
		feat12("descriptorBindingPartiallyBound", 124 /* VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGPARTIALLYBOUND */),
		feat12("descriptorBindingVariableDescriptorCount", 128 /* VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGVARIABLEDESCRIPTORCOUNT */),
		feat12("runtimeDescriptorArray", 132 /* VkPhysicalDeviceVulkan12Features.RUNTIMEDESCRIPTORARRAY */),
		feat12("samplerFilterMinmax", 136 /* VkPhysicalDeviceVulkan12Features.SAMPLERFILTERMINMAX */),
		feat12("scalarBlockLayout", 140 /* VkPhysicalDeviceVulkan12Features.SCALARBLOCKLAYOUT */),
		feat12("imagelessFramebuffer", 144 /* VkPhysicalDeviceVulkan12Features.IMAGELESSFRAMEBUFFER */),
		feat12("uniformBufferStandardLayout", 148 /* VkPhysicalDeviceVulkan12Features.UNIFORMBUFFERSTANDARDLAYOUT */),
		feat12("shaderSubgroupExtendedTypes", 152 /* VkPhysicalDeviceVulkan12Features.SHADERSUBGROUPEXTENDEDTYPES */),
		feat12("separateDepthStencilLayouts", 156 /* VkPhysicalDeviceVulkan12Features.SEPARATEDEPTHSTENCILLAYOUTS */),
		feat12("hostQueryReset", 160 /* VkPhysicalDeviceVulkan12Features.HOSTQUERYRESET */),
		feat12("timelineSemaphore", 164 /* VkPhysicalDeviceVulkan12Features.TIMELINESEMAPHORE */),
		feat12("bufferDeviceAddress", 168 /* VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS */),
		feat12("bufferDeviceAddressCaptureReplay", 172 /* VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESSCAPTUREREPLAY */),
		feat12("bufferDeviceAddressMultiDevice", 176 /* VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESSMULTIDEVICE */),
		feat12("vulkanMemoryModel", 180 /* VkPhysicalDeviceVulkan12Features.VULKANMEMORYMODEL */),
		feat12("vulkanMemoryModelDeviceScope", 184 /* VkPhysicalDeviceVulkan12Features.VULKANMEMORYMODELDEVICESCOPE */),
		feat12("vulkanMemoryModelAvailabilityVisibilityChains", 188 /* VkPhysicalDeviceVulkan12Features.VULKANMEMORYMODELAVAILABILITYVISIBILITYCHAINS */),
		feat12("shaderOutputViewportIndex", 192 /* VkPhysicalDeviceVulkan12Features.SHADEROUTPUTVIEWPORTINDEX */),
		feat12("shaderOutputLayer", 196 /* VkPhysicalDeviceVulkan12Features.SHADEROUTPUTLAYER */),
		feat12("subgroupBroadcastDynamicId", 200 /* VkPhysicalDeviceVulkan12Features.SUBGROUPBROADCASTDYNAMICID */),

		// getVkPhysicalDeviceVulkan13Features, in header order; robustImageAccess appears twice
		// in the SDK header and the table keeps the first entry.
		feat13("robustImageAccess", 16 /* VkPhysicalDeviceVulkan13Features.ROBUSTIMAGEACCESS */),
		feat13("inlineUniformBlock", 20 /* VkPhysicalDeviceVulkan13Features.INLINEUNIFORMBLOCK */),
		feat13("descriptorBindingInlineUniformBlockUpdateAfterBind", 24 /* VkPhysicalDeviceVulkan13Features.DESCRIPTORBINDINGINLINEUNIFORMBLOCKUPDATEAFTERBIND */),
		feat13("pipelineCreationCacheControl", 28 /* VkPhysicalDeviceVulkan13Features.PIPELINECREATIONCACHECONTROL */),
		feat13("privateData", 32 /* VkPhysicalDeviceVulkan13Features.PRIVATEDATA */),
		feat13("shaderDemoteToHelperInvocation", 36 /* VkPhysicalDeviceVulkan13Features.SHADERDEMOTETOHELPERINVOCATION */),
		feat13("shaderTerminateInvocation", 40 /* VkPhysicalDeviceVulkan13Features.SHADERTERMINATEINVOCATION */),
		feat13("subgroupSizeControl", 44 /* VkPhysicalDeviceVulkan13Features.SUBGROUPSIZECONTROL */),
		feat13("computeFullSubgroups", 48 /* VkPhysicalDeviceVulkan13Features.COMPUTEFULLSUBGROUPS */),
		feat13("synchronization2", 52 /* VkPhysicalDeviceVulkan13Features.SYNCHRONIZATION2 */),
		feat13("textureCompressionASTC_HDR", 56 /* VkPhysicalDeviceVulkan13Features.TEXTURECOMPRESSIONASTC_HDR */),
		feat13("shaderZeroInitializeWorkgroupMemory", 60 /* VkPhysicalDeviceVulkan13Features.SHADERZEROINITIALIZEWORKGROUPMEMORY */),
		feat13("dynamicRendering", 64 /* VkPhysicalDeviceVulkan13Features.DYNAMICRENDERING */),
		feat13("shaderIntegerDotProduct", 68 /* VkPhysicalDeviceVulkan13Features.SHADERINTEGERDOTPRODUCT */),
		feat13("maintenance4", 72 /* VkPhysicalDeviceVulkan13Features.MAINTENANCE4 */)
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