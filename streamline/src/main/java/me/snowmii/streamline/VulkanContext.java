package me.snowmii.streamline;

import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Passive holder for the live Vulkan instance / device / graphics queue that Streamline's
 * manual-hook integration must be told about, plus a source of recording command buffers for
 * DLSS work.
 *
 * <p>The engine-free home of the context: everything here is JDK plus {@code org.lwjgl.vulkan}
 * (an ordinary Maven artifact, used only as the command-buffer type inside the injected
 * supplier/consumer) - no Minecraft or Blaze3D reference. The {@code fromVulkanDevice} capture
 * factory that reads Minecraft's live {@code VulkanDevice} is mod-side: the mixin builds a
 * context from raw handles through {@link #fromNativeHandles} and hands it to the registry.
 *
 * <p>This object owns no queue submission of its own. {@link #allocateRecordingCommandBuffer()} produces
 * a fresh, non-zero recording command buffer through the injected shared-encoder source, and
 * {@link #enqueueOnEngineEncoder} hands it back to that same encoder, which carries it out with
 * the frame the game was already going to submit. Nothing here waits on a fence or idles the
 * device: the encoder's own timeline is what orders this work.
 */
public final class VulkanContext {
	private final long instanceHandle;
	private final long physicalDeviceHandle;
	private final long deviceHandle;
	private final long graphicsQueueHandle;
	/** Queue family the graphics queue lives in, for Streamline's manual-hook Vulkan info. */
	private final int graphicsQueueFamily;
	/** Index at which Streamline's own queues start: the number of queues the host created in {@link #graphicsQueueFamily}. */
	private final int graphicsQueueIndex;
	/** Queue family the compute queue lives in, for Streamline's manual-hook Vulkan info. */
	private final int computeQueueFamily;
	/** Index at which Streamline's own compute queues start: the number of queues the host created in {@link #computeQueueFamily}. */
	private final int computeQueueIndex;
	private final Supplier<VkCommandBuffer> recordingCommandBufferSource;
	private final Consumer<VkCommandBuffer> submittedCommandBufferSink;

	private VulkanContext(
		final long instanceHandle,
		final long physicalDeviceHandle,
		final long deviceHandle,
		final long graphicsQueueHandle,
		final int graphicsQueueFamily,
		final int graphicsQueueIndex,
		final int computeQueueFamily,
		final int computeQueueIndex,
		final Supplier<VkCommandBuffer> commandBufferSource,
		final Consumer<VkCommandBuffer> commandBufferSink
	) {
		if (instanceHandle == 0L) {
			throw new IllegalArgumentException("Vulkan instance handle must be non-zero");
		}
		if (physicalDeviceHandle == 0L) {
			throw new IllegalArgumentException("Vulkan physical device handle must be non-zero");
		}
		if (deviceHandle == 0L) {
			throw new IllegalArgumentException("Vulkan device handle must be non-zero");
		}
		if (graphicsQueueHandle == 0L) {
			throw new IllegalArgumentException("Vulkan graphics queue handle must be non-zero");
		}
		Objects.requireNonNull(commandBufferSource, "command buffer source must be provided");
		Objects.requireNonNull(commandBufferSink, "command buffer sink must be provided");
		this.instanceHandle = instanceHandle;
		this.physicalDeviceHandle = physicalDeviceHandle;
		this.deviceHandle = deviceHandle;
		this.graphicsQueueHandle = graphicsQueueHandle;
		this.graphicsQueueFamily = graphicsQueueFamily;
		this.graphicsQueueIndex = graphicsQueueIndex;
		this.computeQueueFamily = computeQueueFamily;
		this.computeQueueIndex = computeQueueIndex;
		this.recordingCommandBufferSource = commandBufferSource;
		this.submittedCommandBufferSink = commandBufferSink;
	}

	public long getInstanceHandle() {
		return instanceHandle;
	}

	public long getPhysicalDeviceHandle() {
		return physicalDeviceHandle;
	}

	public long getDeviceHandle() {
		return deviceHandle;
	}

	public long getGraphicsQueueHandle() {
		return graphicsQueueHandle;
	}

	/** Queue family the graphics queue lives in, for Streamline's manual-hook Vulkan info. */
	public int getGraphicsQueueFamily() {
		return graphicsQueueFamily;
	}

	/** Index at which Streamline's own queues start: the number of queues the host created in the graphics family. */
	public int getGraphicsQueueIndex() {
		return graphicsQueueIndex;
	}

	/** Queue family the compute queue lives in, for Streamline's manual-hook Vulkan info. */
	public int getComputeQueueFamily() {
		return computeQueueFamily;
	}

	/** Index at which Streamline's own compute queues start: the number of queues the host created in the compute family. */
	public int getComputeQueueIndex() {
		return computeQueueIndex;
	}

	/** Records a fresh command buffer from the injected source, returning its non-zero handle wrapper. */
	public VkCommandBuffer allocateRecordingCommandBuffer() {
		return recordingCommandBufferSource.get();
	}

	/**
	 * Ends {@code commandBuffer} and enqueues it on the frame the host is already assembling.
	 *
	 * <p>The buffer runs after everything the encoder recorded before this call, which is what
	 * puts DLSS work behind the world render it consumes without a submission of its own.
	 */
	public void enqueueOnEngineEncoder(final VkCommandBuffer commandBuffer) {
		submittedCommandBufferSink.accept(commandBuffer);
	}

	/**
	 * Test / bootstrap seam: build a context from raw native handles plus an explicit
	 * command-buffer source. Used by the mod's capture factory (which reads the game's live
	 * {@code VulkanDevice}) and by {@code VulkanContextAccessTest} against a self-built
	 * headless Vulkan context, and usable by a future headless/native bootstrap.
	 */
	public static VulkanContext fromNativeHandles(
		final long instance,
		final long vkPhysicalDevice,
		final long vkDevice,
		final long vkQueue,
		final int graphicsQueueFamily,
		final int graphicsQueueIndex,
		final int computeQueueFamily,
		final int computeQueueIndex,
		final Supplier<VkCommandBuffer> commandBufferSource,
		final Consumer<VkCommandBuffer> commandBufferSink
	) {
		return new VulkanContext(
			instance,
			vkPhysicalDevice,
			vkDevice,
			vkQueue,
			graphicsQueueFamily,
			graphicsQueueIndex,
			computeQueueFamily,
			computeQueueIndex,
			commandBufferSource,
			commandBufferSink
		);
	}

	/**
	 * The same seam with a no-op command-buffer sink, mirroring the Kotlin default of the
	 * replaced companion function for the callers that only record and never submit.
	 */
	public static VulkanContext fromNativeHandles(
		final long instance,
		final long vkPhysicalDevice,
		final long vkDevice,
		final long vkQueue,
		final int graphicsQueueFamily,
		final int graphicsQueueIndex,
		final int computeQueueFamily,
		final int computeQueueIndex,
		final Supplier<VkCommandBuffer> commandBufferSource
	) {
		return new VulkanContext(
			instance,
			vkPhysicalDevice,
			vkDevice,
			vkQueue,
			graphicsQueueFamily,
			graphicsQueueIndex,
			computeQueueFamily,
			computeQueueIndex,
			commandBufferSource,
			commandBuffer -> {
			}
		);
	}
}
