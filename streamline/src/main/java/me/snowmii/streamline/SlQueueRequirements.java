package me.snowmii.streamline;

/**
 * The extra Vulkan queues Streamline's loaded features (DLSS, DLSS-G, Reflex) require the host
 * to create, summed across features as {@code slGetFeatureRequirements} reports them.
 *
 * <p>The host merges {@code graphicsQueues} into the graphics queue family and
 * {@code computeQueues} into the compute queue family of its own device creation, and
 * Streamline's queues start right after the host's in each family. {@code opticalFlowQueues}
 * is reported but not created: the manual-hooking contract leaves native optical-flow families
 * optional, and DLSS-G falls back to interop mode when the host creates none.
 */
public record SlQueueRequirements(
	/** Extra queues to create in the graphics queue family. */
	int graphicsQueues,
	/** Extra queues to create in the compute queue family. */
	int computeQueues,
	/** Extra optical-flow queues the loaded features would use; not created by this mod. */
	int opticalFlowQueues
) {}