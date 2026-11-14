package me.snowmii.dlss.vk;

import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import java.util.HashSet;
import java.util.Set;

public final class DeviceExtensionRules {
	private DeviceExtensionRules() {
	}

	public static FeatureSet normalize(final FeatureSet featureSet) {
		final Set<String> extensions = new HashSet<>(featureSet.extensions());
		normalize(extensions);
		return new FeatureSet(featureSet.name(), extensions, featureSet.features(), featureSet.condition());
	}

	public static void normalize(final Set<String> extensions) {
		// KHR supersedes EXT; enabling both is forbidden with the Vulkan 1.2 feature.
		if (extensions.contains("VK_KHR_buffer_device_address")) {
			extensions.remove("VK_EXT_buffer_device_address");
		}
		if (extensions.contains("VK_NV_optical_flow")) {
			extensions.add("VK_KHR_format_feature_flags2");
		}
	}
}
