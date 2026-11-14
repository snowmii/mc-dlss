package me.snowmii.dlss.vk;

import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.util.ArrayList;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Passive Vulkan handles plus a way to record work onto Minecraft's shared encoder.
 *
 * <p>Engine-free: JDK + LWJGL Vulkan types only. Capture from {@code VulkanDevice} lives
 * in the Fabric module ({@code VulkanContextCapture}).
 *
 * <p>Production contexts retain the live {@link VkDevice} and {@link VkPhysicalDevice}
 * so escape-hatch code can call device entry points without re-wrapping handles.
 *
 * <p>This object does not submit. {@link #allocateRecordingCommandBuffer()} begins a
 * transient buffer; {@link #enqueueOnEngineEncoder} ends it and appends it to the frame
 * the game was already going to submit.
 */
public final class VulkanContext {
	private static final AtomicLong NEXT_CONTEXT_ID = new AtomicLong();

	private final long contextId;
	private final long instanceHandle;
	private final VkPhysicalDevice vkPhysicalDevice;
	private final VkDevice vkDevice;
	private final long physicalDeviceHandle;
	private final long deviceHandle;
	private final long graphicsQueueHandle;
	private final int graphicsQueueFamily;
	private final int graphicsQueueIndex;
	private final int computeQueueFamily;
	private final int computeQueueIndex;
	private final Supplier<VkCommandBuffer> recordingCommandBufferSource;
	private final Consumer<VkCommandBuffer> submittedCommandBufferSink;
	private final Consumer<Runnable> completedSubmitRetirementSink;
	private final AtomicLong submittedToken = new AtomicLong();
	private final AtomicLong completedToken = new AtomicLong();
	private final NavigableMap<Long, ArrayList<Runnable>> retirements = new TreeMap<>();

	private VulkanContext(
		final long instanceHandle,
		final VkPhysicalDevice vkPhysicalDevice,
		final VkDevice vkDevice,
		final long physicalDeviceHandle,
		final long deviceHandle,
		final long graphicsQueueHandle,
		final int graphicsQueueFamily,
		final int graphicsQueueIndex,
		final int computeQueueFamily,
		final int computeQueueIndex,
		final Supplier<VkCommandBuffer> commandBufferSource,
		final Consumer<VkCommandBuffer> commandBufferSink,
		final Consumer<Runnable> completedSubmitRetirementSink
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
		this.contextId = NEXT_CONTEXT_ID.incrementAndGet();
		this.instanceHandle = instanceHandle;
		this.vkPhysicalDevice = vkPhysicalDevice;
		this.vkDevice = vkDevice;
		this.physicalDeviceHandle = physicalDeviceHandle;
		this.deviceHandle = deviceHandle;
		this.graphicsQueueHandle = graphicsQueueHandle;
		this.graphicsQueueFamily = graphicsQueueFamily;
		this.graphicsQueueIndex = graphicsQueueIndex;
		this.computeQueueFamily = computeQueueFamily;
		this.computeQueueIndex = computeQueueIndex;
		this.recordingCommandBufferSource = commandBufferSource;
		this.submittedCommandBufferSink = commandBufferSink;
		this.completedSubmitRetirementSink = completedSubmitRetirementSink;
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

	public VkPhysicalDevice getVkPhysicalDevice() {
		return vkPhysicalDevice;
	}

	public VkDevice getVkDevice() {
		return vkDevice;
	}

	/** Live logical device from capture; required for Vulkan entry points with dispatch tables. */
	public VkDevice requireVkDevice() {
		if (vkDevice == null) {
			throw new IllegalStateException("VulkanContext has no live VkDevice (handle-only context)");
		}
		return vkDevice;
	}

	/** Live physical device from capture. */
	public VkPhysicalDevice requireVkPhysicalDevice() {
		if (vkPhysicalDevice == null) {
			throw new IllegalStateException("VulkanContext has no live VkPhysicalDevice (handle-only context)");
		}
		return vkPhysicalDevice;
	}

	public long getGraphicsQueueHandle() {
		return graphicsQueueHandle;
	}

	public int getGraphicsQueueFamily() {
		return graphicsQueueFamily;
	}

	/**
	 * Index where extra graphics queues (reserved after the host queues) start,
	 * after the host/Minecraft queues in that family.
	 */
	public int getGraphicsQueueIndex() {
		return graphicsQueueIndex;
	}

	public int getComputeQueueFamily() {
		return computeQueueFamily;
	}

	/**
	 * Index where extra compute queues start, or {@code 0} when no compute family was found.
	 */
	public int getComputeQueueIndex() {
		return computeQueueIndex;
	}

	/** Fresh recording buffer from the injected source; handle is non-zero. */
	public VkCommandBuffer allocateRecordingCommandBuffer() {
		return recordingCommandBufferSource.get();
	}

	/**
	 * Ends {@code commandBuffer} and enqueues it on the frame the host is already assembling.
	 */
	public GpuCompletionToken enqueueOnEngineEncoder(final VkCommandBuffer commandBuffer) {
		submittedCommandBufferSink.accept(commandBuffer);
		final long value = submittedToken.incrementAndGet();
		if (completedSubmitRetirementSink != null) {
			completedSubmitRetirementSink.accept(() -> complete(value));
		}
		return new GpuCompletionToken(contextId, value);
	}

	/** Latest submission token issued by this context. */
	public GpuCompletionToken lastSubmittedToken() {
		return new GpuCompletionToken(contextId, submittedToken.get());
	}

	/** Latest submission known complete by engine callback ordering. */
	public GpuCompletionToken completedToken() {
		return new GpuCompletionToken(contextId, completedToken.get());
	}

	public boolean isComplete(final GpuCompletionToken token) {
		requireOwnToken(token);
		return completedToken.get() >= token.value();
	}

	private void requireOwnToken(final GpuCompletionToken token) {
		Objects.requireNonNull(token, "token");
		if (token.contextId() != contextId) {
			throw new IllegalArgumentException("Completion token belongs to another Vulkan context");
		}
		if (token.value() > submittedToken.get()) {
			throw new IllegalArgumentException("Completion token has not been submitted");
		}
	}

	/** Adds destruction to the token-keyed free list. */
	public void retire(final GpuCompletionToken token, final Runnable destroy) {
		requireOwnToken(token);
		Objects.requireNonNull(destroy, "destroy");
		if (completedSubmitRetirementSink == null && !isComplete(token)) {
			throw new IllegalStateException("VulkanContext has no engine completion source");
		}
		boolean runNow;
		synchronized (retirements) {
			runNow = completedToken.get() >= token.value();
			if (!runNow) retirements.computeIfAbsent(token.value(), ignored -> new ArrayList<>()).add(destroy);
		}
		if (runNow) destroy.run();
	}

	private void complete(final long value) {
		completedToken.accumulateAndGet(value, Math::max);
		final ArrayList<Runnable> ready = new ArrayList<>();
		synchronized (retirements) {
			retirements.headMap(completedToken.get(), true).values().forEach(ready::addAll);
			retirements.headMap(completedToken.get(), true).clear();
		}
		Throwable failure = null;
		for (final Runnable retirement : ready) {
			try {
				retirement.run();
			} catch (RuntimeException | Error thrown) {
				if (failure == null) failure = thrown; else failure.addSuppressed(thrown);
			}
		}
		if (failure instanceof RuntimeException runtime) throw runtime;
		if (failure instanceof Error error) throw error;
	}

	/** Waits until device work completes. Use only before immediate resource destruction. */
	public void waitForDeviceIdle() {
		final int result = VK10.vkDeviceWaitIdle(requireVkDevice());
        if (result != VK10.VK_SUCCESS) throw new IllegalStateException("vkDeviceWaitIdle failed with VkResult " + result);
	}

	/** Advances token retirements after caller proved device idle. */
	public void completeAfterDeviceIdle() {
		complete(submittedToken.get());
	}

	public static VulkanContext fromCapturedDevice(
		final long instance,
		final VkPhysicalDevice vkPhysicalDevice,
		final VkDevice vkDevice,
		final long vkQueue,
		final int graphicsQueueFamily,
		final int graphicsQueueIndex,
		final int computeQueueFamily,
		final int computeQueueIndex,
		final Supplier<VkCommandBuffer> commandBufferSource,
		final Consumer<VkCommandBuffer> commandBufferSink,
		final Consumer<Runnable> completedSubmitRetirementSink
	) {
		Objects.requireNonNull(vkPhysicalDevice, "physical device must be provided");
		Objects.requireNonNull(vkDevice, "device must be provided");
		return new VulkanContext(
			instance,
			vkPhysicalDevice,
			vkDevice,
			vkPhysicalDevice.address(),
			vkDevice.address(),
			vkQueue,
			graphicsQueueFamily,
			graphicsQueueIndex,
			computeQueueFamily,
			computeQueueIndex,
			commandBufferSource,
			commandBufferSink,
			Objects.requireNonNull(completedSubmitRetirementSink, "completion retirement sink must be provided")
		);
	}

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
		final Consumer<VkCommandBuffer> commandBufferSink,
		final Consumer<Runnable> completedSubmitRetirementSink
	) {
		return new VulkanContext(
			instance,
			null,
			null,
			vkPhysicalDevice,
			vkDevice,
			vkQueue,
			graphicsQueueFamily,
			graphicsQueueIndex,
			computeQueueFamily,
			computeQueueIndex,
			commandBufferSource,
			commandBufferSink,
			completedSubmitRetirementSink
		);
	}

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
		return fromNativeHandles(
			instance, vkPhysicalDevice, vkDevice, vkQueue,
			graphicsQueueFamily, graphicsQueueIndex, computeQueueFamily, computeQueueIndex,
			commandBufferSource, commandBufferSink, null
		);
	}

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
		return fromNativeHandles(
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
