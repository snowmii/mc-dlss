package me.snowmii.dlss;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkClearDepthStencilValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkSubmitInfo;

/**
 * Test-only helper building a real, headless Vulkan instance + device with one graphics
 * queue plus a command pool the test can allocate recording command buffers from.
 *
 * Mirrors what Minecraft's VulkanDevice exposes to the mod: a live VkInstance, VkDevice,
 * VkQueue, and a command-buffer source (Minecraft's is the shared VulkanCommandEncoder;
 * here it is a self-owned pool). The path and each LWJGL quirk below were proven working
 * on this workstation by the scout probe.
 */
public final class HeadlessVulkanFixture implements AutoCloseable {
	private final VkInstance instance;
	private final VkDevice device;
	private final VkQueue queue;
	private final VkPhysicalDevice physicalDevice;
	private final long commandPool;
	private final int queueFamilyIndex;
	private final List<Long> allocatedCommandBuffers = new ArrayList<>();
	private final List<EngineImage> ownedImages = new ArrayList<>();
	private final List<String> validationErrors = Collections.synchronizedList(new ArrayList<>());
	private final boolean validationEnabled;
	private final VkDebugUtilsMessengerCallbackEXT validationCallback;
	private final long validationMessenger;

	public HeadlessVulkanFixture() {
		this(List.of(), (ignoredInstance, ignoredDevice) -> List.of());
	}

	/**
	 * Builds a real headless Vulkan instance and device injecting the supplied NGX-required
	 * extensions at creation, mirroring the production bootstrap mixins. Instance extensions are
	 * known before instance creation; device extensions are queried against the live instance and
	 * physical device (the same order Minecraft 26.2 uses), supplied by the provider before device
	 * creation.
	 */
	public HeadlessVulkanFixture(
		final List<String> instanceExtensions,
		final BiFunction<Long, Long, List<String>> deviceExtensionProvider
	) {
		this(instanceExtensions, deviceExtensionProvider, false);
	}

	/**
	 * As above, additionally enabling the Khronos validation layer when {@code validated} is set
	 * and the layer plus VK_EXT_debug_utils are installed on this workstation.
	 *
	 * Validation is the only oracle for image layouts: a wrong {@code oldLayout} is undefined
	 * behaviour to the driver and silent without it. Every error-severity message is collected
	 * for the test to assert on, and {@link #validationEnabled()} reports whether the layer was
	 * actually there, so a test can say what its evidence was worth.
	 */
	public HeadlessVulkanFixture(
		final List<String> instanceExtensions,
		final BiFunction<Long, Long, List<String>> deviceExtensionProvider,
		final boolean validated
	) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			validationEnabled = validated
				&& hasInstanceLayer(stack, VALIDATION_LAYER)
				&& hasInstanceExtension(stack, EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
			VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
				.sType$Default()
				.pApplicationName(stack.UTF8("mc-dlss-vulkan-context-test"))
				.applicationVersion(1)
				.pEngineName(stack.UTF8("mc-dlss"))
				.engineVersion(1)
				// Minecraft 26.2 asks for Vulkan 1.2 and refuses any device below it, and NGX's
				// internals lean on 1.2 features (buffer device address, storage-image writes
				// without a format) that a 1.0 device silently cannot serve.
				.apiVersion(VK12.VK_API_VERSION_1_2);

			List<String> requestedExtensions = new ArrayList<>(instanceExtensions);
			if (validationEnabled && !requestedExtensions.contains(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME)) {
				requestedExtensions.add(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
			}

			PointerBuffer instanceExts = null;
			if (!requestedExtensions.isEmpty()) {
				instanceExts = stack.callocPointer(requestedExtensions.size());
				for (String ext : requestedExtensions) {
					instanceExts.put(stack.ASCII(ext));
				}
				instanceExts.flip();
			}

			PointerBuffer layers = null;
			if (validationEnabled) {
				layers = stack.callocPointer(1);
				layers.put(stack.ASCII(VALIDATION_LAYER));
				layers.flip();
			}

			VkInstanceCreateInfo instanceInfo = VkInstanceCreateInfo.calloc(stack)
				.sType$Default()
				.pApplicationInfo(appInfo)
				.ppEnabledLayerNames(layers)
				.ppEnabledExtensionNames(instanceExts);

			PointerBuffer instancePtr = stack.callocPointer(1);
			checkVk(VK10.vkCreateInstance(instanceInfo, null, instancePtr), "vkCreateInstance");
			instance = new VkInstance(instancePtr.get(0), instanceInfo);

			if (validationEnabled) {
				validationCallback = VkDebugUtilsMessengerCallbackEXT.create(
					(severity, types, callbackData, userData) -> {
						VkDebugUtilsMessengerCallbackDataEXT data =
							VkDebugUtilsMessengerCallbackDataEXT.create(callbackData);
						validationErrors.add(data.pMessageString());
						return VK10.VK_FALSE;
					}
				);
				VkDebugUtilsMessengerCreateInfoEXT messengerInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
					.sType$Default()
					.messageSeverity(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
					.messageType(
						EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
							| EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
					)
					.pfnUserCallback(validationCallback);
				LongBuffer messengerPtr = stack.callocLong(1);
				checkVk(
					EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(instance, messengerInfo, null, messengerPtr),
					"vkCreateDebugUtilsMessengerEXT"
				);
				validationMessenger = messengerPtr.get(0);
			} else {
				validationCallback = null;
				validationMessenger = VK10.VK_NULL_HANDLE;
			}

			IntBuffer deviceCount = stack.callocInt(1);
			checkVk(VK10.vkEnumeratePhysicalDevices(instance, deviceCount, null), "vkEnumeratePhysicalDevices");
			if (deviceCount.get(0) == 0) {
				throw new IllegalStateException("No Vulkan physical devices found");
			}
			PointerBuffer physicalDevices = stack.callocPointer(deviceCount.get(0));
			checkVk(VK10.vkEnumeratePhysicalDevices(instance, deviceCount, physicalDevices), "vkEnumeratePhysicalDevices");
			physicalDevice = new VkPhysicalDevice(physicalDevices.get(0), instance);

			IntBuffer familyCount = stack.callocInt(1);
			VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, familyCount, null);
			VkQueueFamilyProperties.Buffer queueFamilies =
				VkQueueFamilyProperties.calloc(familyCount.get(0), stack);
			VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, familyCount, queueFamilies);

			int graphicsFamily = -1;
			for (int i = 0; i < familyCount.get(0); i++) {
				if ((queueFamilies.get(i).queueFlags() & VK10.VK_QUEUE_GRAPHICS_BIT) != 0) {
					graphicsFamily = i;
					break;
				}
			}
			if (graphicsFamily == -1) {
				throw new IllegalStateException("No graphics queue family found");
			}
			queueFamilyIndex = graphicsFamily;

			List<String> deviceExtensions = deviceExtensionProvider.apply(instance.address(), physicalDevice.address());

			// queueCount derives from pQueuePriorities.remaining(); do NOT flip().
			VkDeviceQueueCreateInfo.Buffer queueCreateInfo = VkDeviceQueueCreateInfo.calloc(1, stack);
			queueCreateInfo.get(0).sType$Default();
			queueCreateInfo.get(0).queueFamilyIndex(queueFamilyIndex);
			queueCreateInfo.get(0).pQueuePriorities(stack.callocFloat(1).put(0, 1.0f));

			PointerBuffer deviceExts = null;
			if (deviceExtensions != null && !deviceExtensions.isEmpty()) {
				deviceExts = stack.callocPointer(deviceExtensions.size());
				for (String ext : deviceExtensions) {
					deviceExts.put(stack.ASCII(ext));
				}
				deviceExts.flip();
			}

			// Enable every feature this physical device reports, the way a real client enables the
			// set its renderer needs: NGX's own shaders and allocations require 1.1/1.2 features
			// (buffer device address, storage-image writes without a format) that an extension
			// name alone does not turn on, and without them NGX records work the driver cannot run.
			VkPhysicalDeviceVulkan11Features supported11 = VkPhysicalDeviceVulkan11Features.calloc(stack).sType$Default();
			VkPhysicalDeviceVulkan12Features supported12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
			supported11.pNext(supported12.address());
			VkPhysicalDeviceFeatures2 supportedFeatures = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
			supportedFeatures.pNext(supported11.address());
			VK12.vkGetPhysicalDeviceFeatures2(physicalDevice, supportedFeatures);

			VkDeviceCreateInfo deviceCreateInfo = VkDeviceCreateInfo.calloc(stack)
				.sType$Default()
				.pNext(supportedFeatures.pNext())
				.pQueueCreateInfos(queueCreateInfo)
				.pEnabledFeatures(supportedFeatures.features())
				.ppEnabledExtensionNames(deviceExts);

			PointerBuffer devicePtr = stack.callocPointer(1);
			checkVk(VK10.vkCreateDevice(physicalDevice, deviceCreateInfo, null, devicePtr), "vkCreateDevice");
			device = new VkDevice(devicePtr.get(0), physicalDevice, deviceCreateInfo);

			PointerBuffer queuePtr = stack.callocPointer(1);
			VK10.vkGetDeviceQueue(device, queueFamilyIndex, 0, queuePtr);
			long queueHandle = queuePtr.get(0);
			if (queueHandle == 0L) {
				throw new IllegalStateException("vkGetDeviceQueue returned a null queue");
			}
			queue = new VkQueue(queueHandle, device);

			VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
				.sType$Default()
				.queueFamilyIndex(queueFamilyIndex);
			LongBuffer poolBuf = stack.callocLong(1);
			checkVk(VK10.vkCreateCommandPool(device, poolInfo, null, poolBuf), "vkCreateCommandPool");
			commandPool = poolBuf.get(0);
		}
	}

	/** Raw address of the live instance, as Minecraft's VulkanInstance.vkInstance().address() is. */
	public long instanceAddress() {
		return instance.address();
	}

	/** Raw address of the live device, as Minecraft's VulkanDevice.vkDevice().address() is. */
	public long deviceAddress() {
		return device.address();
	}

	/** Raw address of the live physical device, as Minecraft's VulkanDevice.physicalDevice().address() is. */
	public long physicalDeviceAddress() {
		return physicalDevice.address();
	}

	/** Raw address of the live graphics queue, as Minecraft's VulkanDevice.graphicsQueue().vkQueue().address() is. */
	public long queueAddress() {
		return queue.address();
	}

	/**
	 * Allocates and begins a fresh recording command buffer, standing in for Minecraft's
	 * shared VulkanCommandEncoder.allocateAndBeginTransientCommandBuffer().
	 */
	public VkCommandBuffer allocateAndBeginCommandBuffer() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
				.sType$Default()
				.commandPool(commandPool)
				.level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY)
				.commandBufferCount(1);
			PointerBuffer cmdPtr = stack.callocPointer(1);
			checkVk(VK10.vkAllocateCommandBuffers(device, allocInfo, cmdPtr), "vkAllocateCommandBuffers");
			VkCommandBuffer commandBuffer = new VkCommandBuffer(cmdPtr.get(0), device);

			VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
				.sType$Default()
				.flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
			checkVk(VK10.vkBeginCommandBuffer(commandBuffer, beginInfo), "vkBeginCommandBuffer");

			allocatedCommandBuffers.add(commandBuffer.address());
			return commandBuffer;
		}
	}

	/** Whether the Khronos validation layer is actually running behind this instance. */
	public boolean validationEnabled() {
		return validationEnabled;
	}

	/** Error-severity validation messages seen so far, in arrival order. */
	public List<String> validationErrors() {
		synchronized (validationErrors) {
			return List.copyOf(validationErrors);
		}
	}

	/**
	 * Validation messages naming one of the supplied Vulkan handles.
	 *
	 * Validation reports every handle it complains about in hexadecimal, which is what makes it
	 * possible to separate errors about resources under test from errors about resources some
	 * library allocated privately and manages itself.
	 */
	public List<String> validationErrorsAbout(final long... handles) {
		List<String> matching = new ArrayList<>();
		for (String message : validationErrors()) {
			for (long handle : handles) {
				if (message.contains(Long.toHexString(handle))) {
					matching.add(message);
					break;
				}
			}
		}
		return matching;
	}

	/**
	 * A device-local image with a full view, standing in for one of Minecraft's GpuTextures.
	 *
	 * Handles are raw, in the units the flat native ABI takes them.
	 */
	public record EngineImage(long image, long memory, long view, int format, int aspectMask) {
	}

	/**
	 * Creates an image the way Minecraft's VulkanGpuTexture does and leaves it in the same place:
	 * VK_IMAGE_LAYOUT_GENERAL, transitioned once at creation and never moved again by the engine.
	 */
	public EngineImage createEngineImage(
		final int width,
		final int height,
		final int format,
		final int usage,
		final int aspectMask
	) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
				.sType$Default()
				.imageType(VK10.VK_IMAGE_TYPE_2D)
				.format(format)
				.mipLevels(1)
				.arrayLayers(1)
				.samples(VK10.VK_SAMPLE_COUNT_1_BIT)
				.tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
				.usage(usage)
				.sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
				.initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
			imageInfo.extent().set(width, height, 1);

			LongBuffer imagePtr = stack.callocLong(1);
			checkVk(VK10.vkCreateImage(device, imageInfo, null, imagePtr), "vkCreateImage");
			long image = imagePtr.get(0);

			VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
			VK10.vkGetImageMemoryRequirements(device, image, requirements);
			VkMemoryAllocateInfo allocateInfo = VkMemoryAllocateInfo.calloc(stack)
				.sType$Default()
				.allocationSize(requirements.size())
				.memoryTypeIndex(deviceLocalMemoryType(stack, requirements.memoryTypeBits()));
			LongBuffer memoryPtr = stack.callocLong(1);
			checkVk(VK10.vkAllocateMemory(device, allocateInfo, null, memoryPtr), "vkAllocateMemory");
			long memory = memoryPtr.get(0);
			checkVk(VK10.vkBindImageMemory(device, image, memory, 0), "vkBindImageMemory");

			VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
				.sType$Default()
				.image(image)
				.viewType(VK10.VK_IMAGE_VIEW_TYPE_2D)
				.format(format);
			viewInfo.subresourceRange().set(aspectMask, 0, 1, 0, 1);
			LongBuffer viewPtr = stack.callocLong(1);
			checkVk(VK10.vkCreateImageView(device, viewInfo, null, viewPtr), "vkCreateImageView");

			EngineImage created = new EngineImage(image, memory, viewPtr.get(0), format, aspectMask);
			ownedImages.add(created);

			VkCommandBuffer transition = allocateAndBeginCommandBuffer();
			VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
				.sType$Default()
				.srcAccessMask(0)
				.dstAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT)
				.oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED)
				.newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
				.srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
				.dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
				.image(image);
			VkImageSubresourceRange range = barrier.get(0).subresourceRange();
			range.set(aspectMask, 0, 1, 0, 1);
			VK10.vkCmdPipelineBarrier(
				transition,
				VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
				VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
				0,
				null,
				null,
				barrier
			);
			endSubmitAndWait(transition);
			return created;
		}
	}

	/**
	 * Records a barrier claiming the image is currently in VK_IMAGE_LAYOUT_GENERAL.
	 *
	 * Only legal if whoever touched the image last put it back there, which is what makes this
	 * the assertion for layout restoration when the validation layer is watching.
	 */
	public void recordGeneralLayoutBarrier(final VkCommandBuffer commandBuffer, final EngineImage image) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
				.sType$Default()
				.srcAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT)
				.dstAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT)
				.oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
				.newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
				.srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
				.dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
				.image(image.image());
			barrier.get(0).subresourceRange().set(image.aspectMask(), 0, 1, 0, 1);
			VK10.vkCmdPipelineBarrier(
				commandBuffer,
				VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
				VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
				0,
				null,
				null,
				barrier
			);
		}
	}

	/**
	 * Clears a depth image to a uniform value, so a test can state exactly what depth the
	 * shader under test reads.
	 *
	 * Recorded on the caller's command buffer, and legal in VK_IMAGE_LAYOUT_GENERAL, which is
	 * where {@link #createEngineImage} leaves every image and where Minecraft rests its own.
	 */
	public void recordDepthClear(final VkCommandBuffer commandBuffer, final EngineImage image, final float depth) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkClearDepthStencilValue value = VkClearDepthStencilValue.calloc(stack).depth(depth).stencil(0);
			VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
			range.get(0).set(image.aspectMask(), 0, 1, 0, 1);
			VK10.vkCmdClearDepthStencilImage(
				commandBuffer,
				image.image(),
				VK10.VK_IMAGE_LAYOUT_GENERAL,
				value,
				range
			);
		}
	}

	/**
	 * Clears a colour image to a uniform value, so a test can state exactly what an image held
	 * before the code under test touched it.
	 *
	 * Recorded on the caller's command buffer, and legal in VK_IMAGE_LAYOUT_GENERAL, which is
	 * where both {@link #createEngineImage} and the native bridge rest their colour images.
	 */
	public void recordColorClear(
		final VkCommandBuffer commandBuffer,
		final long image,
		final float red,
		final float green,
		final float blue,
		final float alpha
	) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkClearColorValue value = VkClearColorValue.calloc(stack);
			value.float32(0, red).float32(1, green).float32(2, blue).float32(3, alpha);
			VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
			range.get(0).set(VK10.VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1);
			VK10.vkCmdClearColorImage(commandBuffer, image, VK10.VK_IMAGE_LAYOUT_GENERAL, value, range);
		}
	}

	/**
	 * Reads back an 8-bit RGBA colour image as normalized floats, four per pixel, in row-major
	 * order.
	 *
	 * The same staging-buffer round trip as {@link #readRg16fImage}, and the same layout
	 * discipline: taken from VK_IMAGE_LAYOUT_GENERAL and handed straight back to it.
	 */
	public float[] readRgba8Image(final long image, final int width, final int height) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			final long byteCount = (long)width * height * 4L;
			VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
				.sType$Default()
				.size(byteCount)
				.usage(VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT)
				.sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
			LongBuffer bufferPtr = stack.callocLong(1);
			checkVk(VK10.vkCreateBuffer(device, bufferInfo, null, bufferPtr), "vkCreateBuffer");
			long buffer = bufferPtr.get(0);

			VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
			VK10.vkGetBufferMemoryRequirements(device, buffer, requirements);
			VkMemoryAllocateInfo allocateInfo = VkMemoryAllocateInfo.calloc(stack)
				.sType$Default()
				.allocationSize(requirements.size())
				.memoryTypeIndex(hostVisibleMemoryType(stack, requirements.memoryTypeBits()));
			LongBuffer memoryPtr = stack.callocLong(1);
			checkVk(VK10.vkAllocateMemory(device, allocateInfo, null, memoryPtr), "vkAllocateMemory");
			long memory = memoryPtr.get(0);
			checkVk(VK10.vkBindBufferMemory(device, buffer, memory, 0), "vkBindBufferMemory");

			VkCommandBuffer copy = allocateAndBeginCommandBuffer();
			recordColorLayoutTransition(stack, copy, image, VK10.VK_IMAGE_LAYOUT_GENERAL,
				VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
			VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
			region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
			region.get(0).imageSubresource().set(VK10.VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
			region.get(0).imageOffset().set(0, 0, 0);
			region.get(0).imageExtent().set(width, height, 1);
			VK10.vkCmdCopyImageToBuffer(copy, image, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, region);
			recordColorLayoutTransition(stack, copy, image, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
				VK10.VK_IMAGE_LAYOUT_GENERAL);
			endSubmitAndWait(copy);

			PointerBuffer mapped = stack.callocPointer(1);
			checkVk(VK10.vkMapMemory(device, memory, 0, byteCount, 0, mapped), "vkMapMemory");
			ByteBuffer bytes = MemoryUtil.memByteBuffer(mapped.get(0), (int)byteCount);
			float[] values = new float[width * height * 4];
			for (int index = 0; index < values.length; index++) {
				values[index] = (bytes.get(index) & 0xFF) / 255.0f;
			}
			VK10.vkUnmapMemory(device, memory);
			VK10.vkDestroyBuffer(device, buffer, null);
			VK10.vkFreeMemory(device, memory, null);
			return values;
		}
	}

	/**
	 * Reads back a two-channel half-float colour image as interleaved floats, x then y per
	 * pixel, in row-major order.
	 *
	 * This is the only way to see what a compute dispatch actually wrote: the image is
	 * device-local, so the contents have to travel through a host-visible staging buffer. The
	 * image is taken from VK_IMAGE_LAYOUT_GENERAL and handed straight back to it, so whoever
	 * tracks its layout stays right.
	 */
	public float[] readRg16fImage(final long image, final int width, final int height) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			final long byteCount = (long)width * height * 2L * Short.BYTES;
			VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
				.sType$Default()
				.size(byteCount)
				.usage(VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT)
				.sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
			LongBuffer bufferPtr = stack.callocLong(1);
			checkVk(VK10.vkCreateBuffer(device, bufferInfo, null, bufferPtr), "vkCreateBuffer");
			long buffer = bufferPtr.get(0);

			VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
			VK10.vkGetBufferMemoryRequirements(device, buffer, requirements);
			VkMemoryAllocateInfo allocateInfo = VkMemoryAllocateInfo.calloc(stack)
				.sType$Default()
				.allocationSize(requirements.size())
				.memoryTypeIndex(hostVisibleMemoryType(stack, requirements.memoryTypeBits()));
			LongBuffer memoryPtr = stack.callocLong(1);
			checkVk(VK10.vkAllocateMemory(device, allocateInfo, null, memoryPtr), "vkAllocateMemory");
			long memory = memoryPtr.get(0);
			checkVk(VK10.vkBindBufferMemory(device, buffer, memory, 0), "vkBindBufferMemory");

			VkCommandBuffer copy = allocateAndBeginCommandBuffer();
			recordColorLayoutTransition(stack, copy, image, VK10.VK_IMAGE_LAYOUT_GENERAL,
				VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
			VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
			region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
			region.get(0).imageSubresource().set(VK10.VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
			region.get(0).imageOffset().set(0, 0, 0);
			region.get(0).imageExtent().set(width, height, 1);
			VK10.vkCmdCopyImageToBuffer(copy, image, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, region);
			recordColorLayoutTransition(stack, copy, image, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
				VK10.VK_IMAGE_LAYOUT_GENERAL);
			endSubmitAndWait(copy);

			PointerBuffer mapped = stack.callocPointer(1);
			checkVk(VK10.vkMapMemory(device, memory, 0, byteCount, 0, mapped), "vkMapMemory");
			ShortBuffer halves = MemoryUtil.memByteBuffer(mapped.get(0), (int)byteCount)
				.order(ByteOrder.nativeOrder())
				.asShortBuffer();
			float[] values = new float[width * height * 2];
			for (int index = 0; index < values.length; index++) {
				values[index] = Float.float16ToFloat(halves.get(index));
			}
			VK10.vkUnmapMemory(device, memory);
			VK10.vkDestroyBuffer(device, buffer, null);
			VK10.vkFreeMemory(device, memory, null);
			return values;
		}
	}

	private void recordColorLayoutTransition(
		final MemoryStack stack,
		final VkCommandBuffer commandBuffer,
		final long image,
		final int oldLayout,
		final int newLayout
	) {
		VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
			.sType$Default()
			.srcAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT)
			.dstAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT)
			.oldLayout(oldLayout)
			.newLayout(newLayout)
			.srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
			.dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
			.image(image);
		barrier.get(0).subresourceRange().set(VK10.VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1);
		VK10.vkCmdPipelineBarrier(
			commandBuffer,
			VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
			VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
			0,
			null,
			null,
			barrier
		);
	}

	private int hostVisibleMemoryType(final MemoryStack stack, final int typeBits) {
		VkPhysicalDeviceMemoryProperties properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
		VK10.vkGetPhysicalDeviceMemoryProperties(physicalDevice, properties);
		int required = VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
		for (int i = 0; i < properties.memoryTypeCount(); i++) {
			boolean allowed = (typeBits & (1 << i)) != 0;
			int flags = properties.memoryTypes(i).propertyFlags();
			if (allowed && (flags & required) == required) {
				return i;
			}
		}
		throw new IllegalStateException("No host-visible coherent memory type for typeBits " + typeBits);
	}

	/**
	 * Ends the recording and submits it on the graphics queue, waiting on a fence rather than
	 * idling the device, which is the wait the production path is forbidden from using.
	 */
	public void endSubmitAndWait(final VkCommandBuffer commandBuffer) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			checkVk(VK10.vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer");
			LongBuffer fencePtr = stack.callocLong(1);
			checkVk(
				VK10.vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, fencePtr),
				"vkCreateFence"
			);
			long fence = fencePtr.get(0);
			VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
				.sType$Default()
				.pCommandBuffers(stack.pointers(commandBuffer));
			checkVk(VK10.vkQueueSubmit(queue, submitInfo, fence), "vkQueueSubmit");
			checkVk(
				VK10.vkWaitForFences(device, stack.longs(fence), true, FENCE_TIMEOUT_NANOSECONDS),
				"vkWaitForFences"
			);
			VK10.vkDestroyFence(device, fence, null);
		}
	}

	private int deviceLocalMemoryType(final MemoryStack stack, final int typeBits) {
		VkPhysicalDeviceMemoryProperties properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
		VK10.vkGetPhysicalDeviceMemoryProperties(physicalDevice, properties);
		for (int i = 0; i < properties.memoryTypeCount(); i++) {
			boolean allowed = (typeBits & (1 << i)) != 0;
			int flags = properties.memoryTypes(i).propertyFlags();
			if (allowed && (flags & VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) {
				return i;
			}
		}
		throw new IllegalStateException("No device-local memory type for typeBits " + typeBits);
	}

	private static boolean hasInstanceLayer(final MemoryStack stack, final String layer) {
		IntBuffer count = stack.callocInt(1);
		checkVk(VK10.vkEnumerateInstanceLayerProperties(count, null), "vkEnumerateInstanceLayerProperties");
		VkLayerProperties.Buffer layers = VkLayerProperties.calloc(count.get(0), stack);
		checkVk(VK10.vkEnumerateInstanceLayerProperties(count, layers), "vkEnumerateInstanceLayerProperties");
		for (int i = 0; i < count.get(0); i++) {
			if (layer.equals(layers.get(i).layerNameString())) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasInstanceExtension(final MemoryStack stack, final String extension) {
		IntBuffer count = stack.callocInt(1);
		checkVk(
			VK10.vkEnumerateInstanceExtensionProperties((String) null, count, null),
			"vkEnumerateInstanceExtensionProperties"
		);
		VkExtensionProperties.Buffer extensions = VkExtensionProperties.calloc(count.get(0), stack);
		checkVk(
			VK10.vkEnumerateInstanceExtensionProperties((String) null, count, extensions),
			"vkEnumerateInstanceExtensionProperties"
		);
		for (int i = 0; i < count.get(0); i++) {
			if (extension.equals(extensions.get(i).extensionNameString())) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void close() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			for (EngineImage owned : ownedImages) {
				VK10.vkDestroyImageView(device, owned.view(), null);
				VK10.vkDestroyImage(device, owned.image(), null);
				VK10.vkFreeMemory(device, owned.memory(), null);
			}
			if (!allocatedCommandBuffers.isEmpty()) {
				PointerBuffer buffers = stack.callocPointer(allocatedCommandBuffers.size());
				for (int i = 0; i < allocatedCommandBuffers.size(); i++) {
					buffers.put(i, allocatedCommandBuffers.get(i));
				}
				VK10.vkFreeCommandBuffers(device, commandPool, buffers);
			}
			VK10.vkDestroyCommandPool(device, commandPool, null);
			VK10.vkDestroyDevice(device, null);
			if (validationMessenger != VK10.VK_NULL_HANDLE) {
				EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(instance, validationMessenger, null);
			}
			VK10.vkDestroyInstance(instance, null);
			if (validationCallback != null) {
				validationCallback.free();
			}
		}
	}

	private static void checkVk(int result, String call) {
		if (result != VK10.VK_SUCCESS) {
			throw new IllegalStateException(call + " failed with VkResult " + result);
		}
	}

	private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";
	private static final long FENCE_TIMEOUT_NANOSECONDS = 10_000_000_000L;
}
