package me.snowmii.streamline;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.IntIntPair;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Passive holder for Minecraft 26.2's live Vulkan instance / device / graphics queue,
 * plus a source of recording command buffers drawn from Minecraft's shared
 * {@link com.mojang.blaze3d.vulkan.VulkanCommandEncoder}.
 *
 * <p>Captured once, at {@link VulkanDevice} construction, by
 * {@code me.snowmii.dlss.mixin.VulkanDeviceContextMixin} and kept reachable through
 * {@code me.snowmii.dlss.bridge.VulkanContextRegistry}.
 *
 * <p>This object owns no queue submission of its own. {@link #recordCommandBuffer()} produces
 * a fresh, non-zero recording command buffer through the injected shared-encoder source, and
 * {@link #submitCommandBuffer} hands it back to that same encoder, which carries it out with
 * the frame Minecraft was already going to submit. Nothing here waits on a fence or idles the
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
	/** Queue family Minecraft's compute queue lives in, for Streamline's manual-hook Vulkan info. */
	private final int computeQueueFamily;
	/** Index at which Streamline's own compute queues start: the number of queues the host created in {@link #computeQueueFamily}. */
	private final int computeQueueIndex;
	private final Supplier<VkCommandBuffer> commandBufferSource;
	private final Consumer<VkCommandBuffer> commandBufferSink;

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
		this.instanceHandle = instanceHandle;
		this.physicalDeviceHandle = physicalDeviceHandle;
		this.deviceHandle = deviceHandle;
		this.graphicsQueueHandle = graphicsQueueHandle;
		this.graphicsQueueFamily = graphicsQueueFamily;
		this.graphicsQueueIndex = graphicsQueueIndex;
		this.computeQueueFamily = computeQueueFamily;
		this.computeQueueIndex = computeQueueIndex;
		this.commandBufferSource = commandBufferSource;
		this.commandBufferSink = commandBufferSink;
	}

	/** The captured Vulkan instance handle. */
	public long getInstanceHandle() {
		return instanceHandle;
	}

	/** The captured Vulkan physical device handle. */
	public long getPhysicalDeviceHandle() {
		return physicalDeviceHandle;
	}

	/** The captured Vulkan device handle. */
	public long getDeviceHandle() {
		return deviceHandle;
	}

	/** The captured Vulkan graphics queue handle. */
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

	/** Queue family Minecraft's compute queue lives in, for Streamline's manual-hook Vulkan info. */
	public int getComputeQueueFamily() {
		return computeQueueFamily;
	}

	/** Index at which Streamline's own compute queues start: the number of queues the host created in the compute family. */
	public int getComputeQueueIndex() {
		return computeQueueIndex;
	}

	/** Records a fresh command buffer from the injected source, returning its non-zero handle wrapper. */
	public VkCommandBuffer recordCommandBuffer() {
		return commandBufferSource.get();
	}

	/**
	 * Ends {@code commandBuffer} and enqueues it on the frame Minecraft is already assembling.
	 *
	 * <p>The buffer runs after everything the encoder recorded before this call, which is what
	 * puts DLSS work behind the world render it consumes without a submission of its own.
	 */
	public void submitCommandBuffer(final VkCommandBuffer commandBuffer) {
		commandBufferSink.accept(commandBuffer);
	}

	/**
	 * Production seam: capture Minecraft's live Vulkan context from a constructed
	 * {@link VulkanDevice} (called at ctor TAIL, all fields final). Returns null if any
	 * handle is zero, so the mod degrades gracefully.
	 *
	 * <p>{@link VulkanDevice} exposes no physical-device accessor, so the mixin passes the
	 * constructor argument straight through. NGX initialization needs it.
	 */
	public static VulkanContext fromVulkanDevice(final VulkanDevice device, final VulkanPhysicalDevice physicalDevice) {
		final long instanceHandle = device.instance().vkInstance().address();
		final long physicalDeviceHandle = physicalDevice.vkPhysicalDevice().address();
		final long deviceHandle = device.vkDevice().address();
		final long queueHandle = device.graphicsQueue().vkQueue().address();
		// Minecraft records no graphics queue when no family can present, which is exactly the
		// case that must not reach Streamline's Vulkan info - degrade like the zero handles do.
		final IntIntPair graphicsPair = physicalDevice.graphicsQueueFamilyAndIndex();
		if (graphicsPair == null) {
			return null;
		}
		if (instanceHandle == 0L || physicalDeviceHandle == 0L || deviceHandle == 0L || queueHandle == 0L) {
			return null;
		}
		final VulkanCommandEncoder encoder = device.createCommandEncoder();
		final Int2IntMap queueFamilyMap = physicalDevice.queueFamilyCreateInfoMap();
		final IntIntPair computePair = physicalDevice.computeQueueFamilyAndIndex();
		return new VulkanContext(
			instanceHandle,
			physicalDeviceHandle,
			deviceHandle,
			queueHandle,
			graphicsPair.leftInt(),
			// Streamline's graphicsQueueIndex is where its own queues start, which is after the
			// queues Minecraft created in the family - the host queue COUNT from the create-info
			// map, not the pair's queue index.
			queueFamilyMap.get(graphicsPair.leftInt()),
			// Same semantics for compute: the count of host queues in the compute family, and
			// 0/0 when Minecraft found no compute family (SL then cannot be told one).
			computePair == null ? 0 : computePair.leftInt(),
			computePair == null ? 0 : queueFamilyMap.get(computePair.leftInt()),
			encoder::allocateAndBeginTransientCommandBuffer,
			// execute() ends whatever the encoder was recording and appends this buffer behind
			// it, so the buffer has to be closed here first. It is not submitted by this call;
			// the encoder's next submit carries it with the rest of the frame.
			commandBuffer -> {
				VK12.vkEndCommandBuffer(commandBuffer);
				encoder.execute(commandBuffer);
			}
		);
	}

	/**
	 * Test / bootstrap seam: build a context from raw native handles plus an explicit
	 * command-buffer source. Used by {@code VulkanContextAccessTest} against a self-built
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