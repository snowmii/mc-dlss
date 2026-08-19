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
	int graphicsQueues,
	int computeQueues,
	/** Reported but not created; DLSS-G falls back to interop without a host optical-flow family. */
	int opticalFlowQueues
) {}