package me.snowmii.dlss.bridge

/**
 * The extra Vulkan queues Streamline's loaded features (DLSS, DLSS-G, Reflex) require the host
 * to create, summed across features as `slGetFeatureRequirements` reports them.
 *
 * The host merges [graphicsQueues] into the graphics queue family and [computeQueues] into the
 * compute queue family of its own device creation, and Streamline's queues start right after
 * the host's in each family. [opticalFlowQueues] is reported but not created: the manual-hooking
 * contract leaves native optical-flow families optional, and DLSS-G falls back to interop mode
 * when the host creates none.
 */
data class SlQueueRequirements(
	/** Extra queues to create in the graphics queue family. */
	val graphicsQueues: Int,
	/** Extra queues to create in the compute queue family. */
	val computeQueues: Int,
	/** Extra optical-flow queues the loaded features would use; not created by this mod. */
	val opticalFlowQueues: Int,
)
