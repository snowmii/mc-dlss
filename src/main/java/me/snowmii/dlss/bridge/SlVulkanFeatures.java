package me.snowmii.dlss.bridge;

import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps Streamline's Vulkan feature names to Minecraft's {@link VulkanFeature} records, so the
 * names {@code slGetFeatureRequirements} reports through the bridge can be merged into the
 * enabled-feature set Minecraft passes to {@code vkCreateDevice}.
 *
 * <p>The name tables mirror the complete {@code SL_VK_FEATURE} lists in Streamline 2.12.0's
 * {@code sl_helpers_vk.h} ({@code getVkPhysicalDeviceVulkan12Features} and
 * {@code getVkPhysicalDeviceVulkan13Features}); each name maps to the LWJGL 3.4.1 int offset
 * constant for the same feature (camelCase name, UPPERCASE constant). {@code samplerMirrorClampToEdge}
 * and {@code robustImageAccess} each appear twice in the SDK header; the map dedupes them.
 * A name that has no LWJGL constant would have to be skipped with a comment, but every name in
 * both tables has one (verified against lwjgl-vulkan-3.4.1.jar).
 *
 * <p>The structs are the same VulkanPNextStruct Minecraft's own {@code REQUIRED_DEVICE_FEATURES}
 * uses: sType 51 is {@code VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES} and
 * {@code 1000088000} is {@code VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES}. Minecraft's
 * createDevice walks the feature set and chains any struct that is not already in the pNext
 * chain, so a feature whose struct is missing is created and chained automatically.
 */
public final class SlVulkanFeatures {
	private static final VulkanPNextStruct VULKAN_12_STRUCT =
		new VulkanPNextStruct(51, VkPhysicalDeviceVulkan12Features.SIZEOF);
	private static final VulkanPNextStruct VULKAN_13_STRUCT =
		new VulkanPNextStruct(1000088000, VkPhysicalDeviceVulkan13Features.SIZEOF);

	/**
	 * Feature names Minecraft 26.2 already enables in its own {@code REQUIRED_DEVICE_FEATURES}.
	 * Re-adding them would either duplicate an identical {@link VulkanFeature} record or enable
	 * the same feature through a second struct (synchronization2, dynamicRendering); skipping
	 * keeps the merged set from growing past what the mod adds.
	 */
	private static final Set<String> ALREADY_ENABLED =
		Set.of("timelineSemaphore", "hostQueryReset", "synchronization2", "dynamicRendering");

	private static final Map<String, VulkanFeature> FEATURES_BY_NAME = new LinkedHashMap<>();

	private SlVulkanFeatures() {
	}

	/**
	 * The {@link VulkanFeature} records for the feature names Streamline requires, in
	 * {@code features12} then {@code features13} order, skipping names Minecraft already enables
	 * and deduping by name across both lists.
	 */
	public static List<VulkanFeature> slRequiredFeatures(
		final List<String> features12,
		final List<String> features13
	) {
		final List<VulkanFeature> required = new ArrayList<>();
		final Set<String> seen = new LinkedHashSet<>();
		for (String name : features12) {
			add(required, seen, name);
		}
		for (String name : features13) {
			add(required, seen, name);
		}
		return required;
	}

	private static void add(final List<VulkanFeature> required, final Set<String> seen, final String name) {
		if (!seen.add(name)) {
			return;
		}
		if (ALREADY_ENABLED.contains(name)) {
			return;
		}
		final VulkanFeature feature = FEATURES_BY_NAME.get(name);
		if (feature != null) {
			required.add(feature);
		}
	}

	private static void put12(final String name, final long offset) {
		FEATURES_BY_NAME.put(name, new VulkanFeature(VULKAN_12_STRUCT, name, offset));
	}

	private static void put13(final String name, final long offset) {
		FEATURES_BY_NAME.put(name, new VulkanFeature(VULKAN_13_STRUCT, name, offset));
	}

	static {
		// getVkPhysicalDeviceVulkan12Features, in header order.
		put12("samplerMirrorClampToEdge", VkPhysicalDeviceVulkan12Features.SAMPLERMIRRORCLAMPTOEDGE);
		put12("drawIndirectCount", VkPhysicalDeviceVulkan12Features.DRAWINDIRECTCOUNT);
		put12("storageBuffer8BitAccess", VkPhysicalDeviceVulkan12Features.STORAGEBUFFER8BITACCESS);
		put12("uniformAndStorageBuffer8BitAccess", VkPhysicalDeviceVulkan12Features.UNIFORMANDSTORAGEBUFFER8BITACCESS);
		put12("storagePushConstant8", VkPhysicalDeviceVulkan12Features.STORAGEPUSHCONSTANT8);
		put12("shaderBufferInt64Atomics", VkPhysicalDeviceVulkan12Features.SHADERBUFFERINT64ATOMICS);
		put12("shaderSharedInt64Atomics", VkPhysicalDeviceVulkan12Features.SHADERSHAREDINT64ATOMICS);
		put12("shaderFloat16", VkPhysicalDeviceVulkan12Features.SHADERFLOAT16);
		put12("shaderInt8", VkPhysicalDeviceVulkan12Features.SHADERINT8);
		put12("descriptorIndexing", VkPhysicalDeviceVulkan12Features.DESCRIPTORINDEXING);
		put12("shaderInputAttachmentArrayDynamicIndexing", VkPhysicalDeviceVulkan12Features.SHADERINPUTATTACHMENTARRAYDYNAMICINDEXING);
		put12("shaderUniformTexelBufferArrayDynamicIndexing", VkPhysicalDeviceVulkan12Features.SHADERUNIFORMTEXELBUFFERARRAYDYNAMICINDEXING);
		put12("shaderStorageTexelBufferArrayDynamicIndexing", VkPhysicalDeviceVulkan12Features.SHADERSTORAGETEXELBUFFERARRAYDYNAMICINDEXING);
		put12("shaderUniformBufferArrayNonUniformIndexing", VkPhysicalDeviceVulkan12Features.SHADERUNIFORMBUFFERARRAYNONUNIFORMINDEXING);
		put12("shaderSampledImageArrayNonUniformIndexing", VkPhysicalDeviceVulkan12Features.SHADERSAMPLEDIMAGEARRAYNONUNIFORMINDEXING);
		put12("shaderStorageBufferArrayNonUniformIndexing", VkPhysicalDeviceVulkan12Features.SHADERSTORAGEBUFFERARRAYNONUNIFORMINDEXING);
		put12("shaderStorageImageArrayNonUniformIndexing", VkPhysicalDeviceVulkan12Features.SHADERSTORAGEIMAGEARRAYNONUNIFORMINDEXING);
		put12("shaderInputAttachmentArrayNonUniformIndexing", VkPhysicalDeviceVulkan12Features.SHADERINPUTATTACHMENTARRAYNONUNIFORMINDEXING);
		put12("shaderUniformTexelBufferArrayNonUniformIndexing", VkPhysicalDeviceVulkan12Features.SHADERUNIFORMTEXELBUFFERARRAYNONUNIFORMINDEXING);
		put12("shaderStorageTexelBufferArrayNonUniformIndexing", VkPhysicalDeviceVulkan12Features.SHADERSTORAGETEXELBUFFERARRAYNONUNIFORMINDEXING);
		put12("descriptorBindingUniformBufferUpdateAfterBind", VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGUNIFORMBUFFERUPDATEAFTERBIND);
		put12("descriptorBindingSampledImageUpdateAfterBind", VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGSAMPLEDIMAGEUPDATEAFTERBIND);
		put12("descriptorBindingStorageImageUpdateAfterBind", VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGSTORAGEIMAGEUPDATEAFTERBIND);
		put12("descriptorBindingStorageBufferUpdateAfterBind", VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGSTORAGEBUFFERUPDATEAFTERBIND);
		put12("descriptorBindingUniformTexelBufferUpdateAfterBind", VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGUNIFORMTEXELBUFFERUPDATEAFTERBIND);
		put12("descriptorBindingStorageTexelBufferUpdateAfterBind", VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGSTORAGETEXELBUFFERUPDATEAFTERBIND);
		put12("descriptorBindingUpdateUnusedWhilePending", VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGUPDATEUNUSEDWHILEPENDING);
		put12("descriptorBindingPartiallyBound", VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGPARTIALLYBOUND);
		put12("descriptorBindingVariableDescriptorCount", VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGVARIABLEDESCRIPTORCOUNT);
		put12("runtimeDescriptorArray", VkPhysicalDeviceVulkan12Features.RUNTIMEDESCRIPTORARRAY);
		put12("samplerFilterMinmax", VkPhysicalDeviceVulkan12Features.SAMPLERFILTERMINMAX);
		put12("scalarBlockLayout", VkPhysicalDeviceVulkan12Features.SCALARBLOCKLAYOUT);
		put12("imagelessFramebuffer", VkPhysicalDeviceVulkan12Features.IMAGELESSFRAMEBUFFER);
		put12("uniformBufferStandardLayout", VkPhysicalDeviceVulkan12Features.UNIFORMBUFFERSTANDARDLAYOUT);
		put12("shaderSubgroupExtendedTypes", VkPhysicalDeviceVulkan12Features.SHADERSUBGROUPEXTENDEDTYPES);
		put12("separateDepthStencilLayouts", VkPhysicalDeviceVulkan12Features.SEPARATEDEPTHSTENCILLAYOUTS);
		put12("hostQueryReset", VkPhysicalDeviceVulkan12Features.HOSTQUERYRESET);
		put12("timelineSemaphore", VkPhysicalDeviceVulkan12Features.TIMELINESEMAPHORE);
		put12("bufferDeviceAddress", VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS);
		put12("bufferDeviceAddressCaptureReplay", VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESSCAPTUREREPLAY);
		put12("bufferDeviceAddressMultiDevice", VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESSMULTIDEVICE);
		put12("vulkanMemoryModel", VkPhysicalDeviceVulkan12Features.VULKANMEMORYMODEL);
		put12("vulkanMemoryModelDeviceScope", VkPhysicalDeviceVulkan12Features.VULKANMEMORYMODELDEVICESCOPE);
		put12("vulkanMemoryModelAvailabilityVisibilityChains", VkPhysicalDeviceVulkan12Features.VULKANMEMORYMODELAVAILABILITYVISIBILITYCHAINS);
		put12("shaderOutputViewportIndex", VkPhysicalDeviceVulkan12Features.SHADEROUTPUTVIEWPORTINDEX);
		put12("shaderOutputLayer", VkPhysicalDeviceVulkan12Features.SHADEROUTPUTLAYER);
		put12("subgroupBroadcastDynamicId", VkPhysicalDeviceVulkan12Features.SUBGROUPBROADCASTDYNAMICID);

		// getVkPhysicalDeviceVulkan13Features, in header order; robustImageAccess appears twice
		// in the SDK header and the map keeps the first entry.
		put13("robustImageAccess", VkPhysicalDeviceVulkan13Features.ROBUSTIMAGEACCESS);
		put13("inlineUniformBlock", VkPhysicalDeviceVulkan13Features.INLINEUNIFORMBLOCK);
		put13("descriptorBindingInlineUniformBlockUpdateAfterBind", VkPhysicalDeviceVulkan13Features.DESCRIPTORBINDINGINLINEUNIFORMBLOCKUPDATEAFTERBIND);
		put13("pipelineCreationCacheControl", VkPhysicalDeviceVulkan13Features.PIPELINECREATIONCACHECONTROL);
		put13("privateData", VkPhysicalDeviceVulkan13Features.PRIVATEDATA);
		put13("shaderDemoteToHelperInvocation", VkPhysicalDeviceVulkan13Features.SHADERDEMOTETOHELPERINVOCATION);
		put13("shaderTerminateInvocation", VkPhysicalDeviceVulkan13Features.SHADERTERMINATEINVOCATION);
		put13("subgroupSizeControl", VkPhysicalDeviceVulkan13Features.SUBGROUPSIZECONTROL);
		put13("computeFullSubgroups", VkPhysicalDeviceVulkan13Features.COMPUTEFULLSUBGROUPS);
		put13("synchronization2", VkPhysicalDeviceVulkan13Features.SYNCHRONIZATION2);
		put13("textureCompressionASTC_HDR", VkPhysicalDeviceVulkan13Features.TEXTURECOMPRESSIONASTC_HDR);
		put13("shaderZeroInitializeWorkgroupMemory", VkPhysicalDeviceVulkan13Features.SHADERZEROINITIALIZEWORKGROUPMEMORY);
		put13("dynamicRendering", VkPhysicalDeviceVulkan13Features.DYNAMICRENDERING);
		put13("shaderIntegerDotProduct", VkPhysicalDeviceVulkan13Features.SHADERINTEGERDOTPRODUCT);
		put13("maintenance4", VkPhysicalDeviceVulkan13Features.MAINTENANCE4);
	}
}
