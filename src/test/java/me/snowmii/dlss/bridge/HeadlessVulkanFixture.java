package me.snowmii.dlss.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Path;
import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
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
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.lwjgl.vulkan.VkWin32SurfaceCreateInfoKHR;

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
	/** The hidden Win32 window this fixture presents through, 0 until {@link #createSurface} runs. */
	private long window;
	/** The swapchain created by {@link #createSwapchain}, VK_NULL_HANDLE until then. */
	private long swapchain = VK10.VK_NULL_HANDLE;
	/** The surface created by {@link #createSurface}, VK_NULL_HANDLE until then. */
	private long surface = VK10.VK_NULL_HANDLE;
	/** The binary semaphores handed to acquire/present, destroyed at close. */
	private final List<Long> ownedSemaphores = new ArrayList<>();

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
		this(instanceExtensions, deviceExtensionProvider, validated, Map.of());
	}

	/**
	 * As {@link #HeadlessVulkanFixture(List, BiFunction, boolean)}, additionally creating
	 * {@code additionalQueuesPerFamily} extra queues in each named queue family, on top of the
	 * fixture's base single graphics queue.
	 *
	 * The production merge starts from Minecraft's {@code {graphicsFamily: 1}} queue map and
	 * adds Streamline's required extra graphics/compute queues to it; this overload is what lets
	 * a test build the device whose queue layout that merge produces. Each family gets one
	 * {@code VkDeviceQueueCreateInfo} with {@code queueCount = base + additional} and its own
	 * queue-priorities buffer of that many floats, mirroring how Minecraft's createDevice loop
	 * builds queue creation infos from its family map.
	 */
	public HeadlessVulkanFixture(
		final List<String> instanceExtensions,
		final BiFunction<Long, Long, List<String>> deviceExtensionProvider,
		final boolean validated,
		final Map<Integer, Integer> additionalQueuesPerFamily
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

			// The merged queue layout: the base single graphics queue plus the caller's extra
			// queues per family, one create info per family - the same shape Minecraft's
			// createDevice loop builds from its queue-family map.
			Int2IntMap queuesToCreate = new Int2IntArrayMap();
			queuesToCreate.put(queueFamilyIndex, 1);
			for (Map.Entry<Integer, Integer> extra : additionalQueuesPerFamily.entrySet()) {
				int family = extra.getKey();
				int additional = extra.getValue();
				queuesToCreate.put(family, queuesToCreate.get(family) + additional);
			}
			VkDeviceQueueCreateInfo.Buffer queueCreateInfo = VkDeviceQueueCreateInfo.calloc(queuesToCreate.size(), stack);
			int createInfoIndex = 0;
			for (Int2IntMap.Entry familyQueues : queuesToCreate.int2IntEntrySet()) {
				// queueCount derives from pQueuePriorities.remaining(); do NOT flip().
				queueCreateInfo.get(createInfoIndex).sType$Default();
				queueCreateInfo.get(createInfoIndex).queueFamilyIndex(familyQueues.getIntKey());
				queueCreateInfo.get(createInfoIndex).pQueuePriorities(stack.callocFloat(familyQueues.getIntValue()));
				createInfoIndex++;
			}

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

	/** The queue family the fixture's graphics queue was created in. */
	public int graphicsQueueFamilyIndex() {
		return queueFamilyIndex;
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
	 * The live swapchain this fixture presents through: its handle, the image count the
	 * driver actually created (never fewer than the requested minimum), the raw image
	 * handles, the format they were created in, and their extent.
	 */
	public record Swapchain(long handle, int imageCount, long[] images, int imageFormat, int width, int height) {
	}

	/**
	 * Creates the hidden Win32 window this fixture presents through, sized to the surface
	 * the swapchain will be created against, and the {@code VkSurfaceKHR} on it.
	 *
	 * The window is a real Win32 window the size of the presentation target, never shown,
	 * registered under a fixture-owned window class whose procedure forwards everything to
	 * {@code DefWindowProcW} - the same shape a normal app's window has, so the WSI and the
	 * Streamline interposer see nothing unusual. The creation is routed through
	 * sl.interposer.dll's exported wrapper like the swapchain and present calls: its hook
	 * list names vkCreateWin32SurfaceKHR, and the DLSS-G plugin records the surface-to-HWND
	 * mapping from that hook - the mapping the present path uses to attach the swapchain to
	 * the window it must pace and present to. A surface created through the plain loader
	 * would leave the interposed present path with a swapchain whose surface the plugin
	 * never saw created, and no window to attach to.
	 */
	public long createSurface(final int width, final int height) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			if (surface == VK10.VK_NULL_HANDLE) {
				window = createHiddenWindow(width, height);
				VkWin32SurfaceCreateInfoKHR surfaceInfo = VkWin32SurfaceCreateInfoKHR.calloc(stack)
					.sType$Default()
					.hinstance(windowModule)
					.hwnd(window);
				LongBuffer surfacePtr = stack.callocLong(1);
				checkVk(
					callInterposer(
						INTERPOSER_CREATE_WIN32_SURFACE,
						instance.address(),
						MemorySegment.ofAddress(surfaceInfo.address()),
						MemorySegment.NULL,
						MemorySegment.ofAddress(MemoryUtil.memAddress(surfacePtr))
					),
					"vkCreateWin32SurfaceKHR"
				);
				surface = surfacePtr.get(0);
				IntBuffer supported = stack.callocInt(1);
				checkVk(
					KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR(
						physicalDevice,
						queueFamilyIndex,
						surface,
						supported
					),
					"vkGetPhysicalDeviceSurfaceSupportKHR"
				);
				if (supported.get(0) != VK10.VK_TRUE) {
					throw new IllegalStateException(
						"The fixture's graphics queue family cannot present to the created surface"
					);
				}
			}
			return surface;
		}
	}

	/**
	 * Creates the presentation swapchain against {@code surface} with at least
	 * {@code minImageCount} images at {@code width}x{@code height}.
	 *
	 * The format is pinned to R8G8B8A8_UNORM/SRGB_NONLINEAR - the format the DLSS-G options
	 * record declares as the backbuffer format, so the swapchain's images and the plugin's
	 * internal resources describe the same pixels - and the present mode is IMMEDIATE when
	 * the surface offers it (FIFO otherwise), the vsync-off discipline the FG path records.
	 * The image usage includes SAMPLED because the DLSS-G plugin reads the presented
	 * backbuffer as a texture at present time.
	 *
	 * The creation and the image query are routed through sl.interposer.dll's exported
	 * wrappers rather than the loader's functions: those are the functions Streamline's
	 * hook list names (eCreateSwapchainKHR and eGetSwapchainImagesKHR), and the DLSS-G
	 * plugin reads the swapchain and its images through them. The interposer forwards to
	 * the driver and lets each plugin's hooks see - and, where the plugin needs it,
	 * substitute - the created objects. The instance and device themselves are
	 * deliberately created through the plain loader: the mod's manual-hooking activation
	 * records the device with slSetVulkanInfo, which the interposer's own vkCreateDevice
	 * auto-integration would pre-empt (the SDK answers eErrorInvalidIntegration to a
	 * slSetVulkanInfo that follows it).
	 */
	public Swapchain createSwapchain(
		final long surface,
		final int width,
		final int height,
		final int minImageCount
	) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer formatCount = stack.callocInt(1);
			checkVk(
				KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, formatCount, null),
				"vkGetPhysicalDeviceSurfaceFormatsKHR"
			);
			VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.calloc(formatCount.get(0), stack);
			checkVk(
				KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, formatCount, formats),
				"vkGetPhysicalDeviceSurfaceFormatsKHR"
			);
			int format = 0;
			for (int i = 0; i < formatCount.get(0); i++) {
				if (formats.get(i).format() == VK10.VK_FORMAT_R8G8B8A8_UNORM &&
					formats.get(i).colorSpace() == KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
					format = formats.get(i).format();
					break;
				}
			}
			if (format == 0) {
				throw new IllegalStateException(
					"Surface does not offer R8G8B8A8_UNORM/SRGB_NONLINEAR, the format the DLSS-G " +
						"options declare for the backbuffer"
				);
			}

			IntBuffer modeCount = stack.callocInt(1);
			checkVk(
				KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, modeCount, null),
				"vkGetPhysicalDeviceSurfacePresentModesKHR"
			);
			IntBuffer modes = stack.callocInt(modeCount.get(0));
			checkVk(
				KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, modeCount, modes),
				"vkGetPhysicalDeviceSurfacePresentModesKHR"
			);
			int presentMode = KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
			for (int i = 0; i < modeCount.get(0); i++) {
				if (modes.get(i) == KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR) {
					presentMode = KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR;
					break;
				}
			}

			VkSurfaceCapabilitiesKHR capabilities = VkSurfaceCapabilitiesKHR.calloc(stack);
			checkVk(
				KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, capabilities),
				"vkGetPhysicalDeviceSurfaceCapabilitiesKHR"
			);
			int imageCount = Math.max(minImageCount, capabilities.minImageCount());
			if (capabilities.maxImageCount() != 0 && imageCount > capabilities.maxImageCount()) {
				imageCount = capabilities.maxImageCount();
			}
			// The hidden window is sized to the target, so the driver's reported extent is the
			// requested one; a driver that reports the special free-size marker instead gets the
			// requested size written explicitly.
			int extentWidth = capabilities.currentExtent().width();
			int extentHeight = capabilities.currentExtent().height();
			if (extentWidth == 0xFFFFFFFF || extentHeight == 0xFFFFFFFF) {
				extentWidth = width;
				extentHeight = height;
			}
			if (extentWidth != width || extentHeight != height) {
				throw new IllegalStateException(
					"Surface extent " + extentWidth + "x" + extentHeight + " does not match the " +
						"requested " + width + "x" + height + " (hidden window not sized to the target?)"
				);
			}

			int compositeAlpha = KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
			if ((capabilities.supportedCompositeAlpha() & compositeAlpha) == 0) {
				compositeAlpha = Integer.lowestOneBit(capabilities.supportedCompositeAlpha());
			}

			VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack)
				.sType$Default()
				.surface(surface)
				.minImageCount(imageCount)
				.imageFormat(format)
				.imageColorSpace(KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)
				.imageArrayLayers(1)
				.imageUsage(VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT)
				.imageSharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
				.preTransform(capabilities.currentTransform())
				.compositeAlpha(compositeAlpha)
				.presentMode(presentMode)
				.clipped(true);
			createInfo.imageExtent().set(width, height);
			LongBuffer swapchainPtr = stack.callocLong(1);
			checkVk(
				callInterposer(INTERPOSER_CREATE_SWAPCHAIN, device.address(), MemorySegment.ofAddress(createInfo.address()), MemorySegment.NULL, MemorySegment.ofAddress(MemoryUtil.memAddress(swapchainPtr))),
				"vkCreateSwapchainKHR"
			);
			swapchain = swapchainPtr.get(0);

			IntBuffer imageCountBuf = stack.callocInt(1);
			checkVk(
				callInterposer(INTERPOSER_GET_SWAPCHAIN_IMAGES, device.address(), swapchain, MemorySegment.ofAddress(MemoryUtil.memAddress(imageCountBuf)), MemorySegment.NULL),
				"vkGetSwapchainImagesKHR"
			);
			LongBuffer imagesBuf = stack.callocLong(imageCountBuf.get(0));
			checkVk(
				callInterposer(INTERPOSER_GET_SWAPCHAIN_IMAGES, device.address(), swapchain, MemorySegment.ofAddress(MemoryUtil.memAddress(imageCountBuf)), MemorySegment.ofAddress(MemoryUtil.memAddress(imagesBuf))),
				"vkGetSwapchainImagesKHR"
			);
			long[] images = new long[imageCountBuf.get(0)];
			for (int i = 0; i < images.length; i++) {
				images[i] = imagesBuf.get(i);
			}
			return new Swapchain(swapchain, images.length, images, format, width, height);
		}
	}

	/**
	 * One acquired swapchain image: its index and the binary semaphore the DLSS-G plugin
	 * signals when its workload for the previous frame is submitted (the acquire semaphore
	 * of the DLSS-G Vulkan contract). The signal is consumed by the frame's own submission:
	 * {@link #submitAndSignal(VkCommandBuffer, long, long)} waits on it in the submit, so
	 * the plugin's previous-frame workloads complete before the new frame's commands
	 * execute. No host wait is involved.
	 */
	public record AcquiredImage(int index, long semaphore) {
	}

	/**
	 * Acquires the next swapchain image through the interposer, returning its index and the
	 * acquire semaphore the DLSS-G plugin signals.
	 *
	 * This is the DLSS-G Vulkan contract's acquire side: the plugin signals the binary
	 * semaphore handed to vkAcquireNextImageKHR when its workloads are submitted, and the
	 * frame that uses the image must wait on that signal before its commands execute. The
	 * wait belongs in the frame's queue submission (a VkSubmitInfo wait semaphore, which is
	 * what {@link #submitAndSignal(VkCommandBuffer, long, long)} does), not on the host:
	 * the acquire is a binary signal, and the GPU wait is what gives it meaning. The
	 * semaphore is fixture-owned and destroyed at {@link #close()}, after the queue's last
	 * submit has been fence-waited.
	 */
	public AcquiredImage acquireNextImage(final long swapchain) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer semaphorePtr = stack.callocLong(1);
			checkVk(
				VK10.vkCreateSemaphore(device, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, semaphorePtr),
				"vkCreateSemaphore"
			);
			long semaphore = semaphorePtr.get(0);
			ownedSemaphores.add(semaphore);
			IntBuffer indexPtr = stack.callocInt(1);
			int result = callInterposer(
				INTERPOSER_ACQUIRE_NEXT_IMAGE,
				device.address(),
				swapchain,
				FENCE_TIMEOUT_NANOSECONDS,
				semaphore,
				0L, // no fence: the plugin signals the semaphore, which the frame's submit waits on
				MemorySegment.ofAddress(MemoryUtil.memAddress(indexPtr))
			);
			if (result != VK10.VK_SUCCESS) {
				throw new IllegalStateException("vkAcquireNextImageKHR failed with VkResult " + result);
			}
			// No host wait here: the semaphore is binary and the signal's only consumer is the
			// queue submission that waits on it (submitAndSignal's pWaitSemaphores). Recording
			// commands against the image needs no readiness - only execution does, and the
			// submit orders that.
			return new AcquiredImage(indexPtr.get(0), semaphore);
		}
	}

	/**
	 * Creates one binary semaphore for the frame's present handshake, owned and destroyed by
	 * the fixture.
	 */
	public long createBinarySemaphore() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer semaphorePtr = stack.callocLong(1);
			checkVk(
				VK10.vkCreateSemaphore(device, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, semaphorePtr),
				"vkCreateSemaphore"
			);
			ownedSemaphores.add(semaphorePtr.get(0));
			return semaphorePtr.get(0);
		}
	}

	/**
	 * Ends the recording and submits it on the graphics queue, waiting on a fence.
	 *
	 * The submit waits on {@code waitSemaphore} - the acquire semaphore of the DLSS-G
	 * Vulkan contract, signaled when the acquired image is ready for the frame - before the
	 * frame's commands execute, and signals {@code signalSemaphore} - the present semaphore
	 * of the contract, which the plugin's present processing waits on before adding its
	 * workloads. The acquire semaphore is binary, so the queue consumes its signal in this
	 * submit; the fixture keeps ownership of both semaphores and destroys them at
	 * {@link #close()}, after the fence wait has made every queued use complete.
	 */
	public void submitAndSignal(
		final VkCommandBuffer commandBuffer,
		final long waitSemaphore,
		final long signalSemaphore
	) {
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
				// The frame cannot touch the acquired image until the acquire signal arrives;
				// the stage mask places that wait before the frame's colour writes.
				.pWaitSemaphores(stack.longs(waitSemaphore))
				.pWaitDstStageMask(stack.ints(VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
				.pCommandBuffers(stack.pointers(commandBuffer))
				.pSignalSemaphores(stack.longs(signalSemaphore));
			checkVk(VK10.vkQueueSubmit(queue, submitInfo, fence), "vkQueueSubmit");
			checkVk(
				VK10.vkWaitForFences(device, stack.longs(fence), true, FENCE_TIMEOUT_NANOSECONDS),
				"vkWaitForFences"
			);
			VK10.vkDestroyFence(device, fence, null);
		}
	}

	/**
	 * Records the transition of an acquired swapchain image into the present layout, the
	 * state the presenting queue must hand the image to vkQueuePresentKHR in. The old layout
	 * is UNDEFINED: the test never renders into the backbuffer (the DLSS-G plugin reads and
	 * writes it at present time), so the contents are discarded on first use and on every
	 * reuse of the image.
	 */
	public void recordPresentLayoutTransition(final VkCommandBuffer commandBuffer, final long image) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			recordColorLayoutTransition(
				stack,
				commandBuffer,
				image,
				VK10.VK_IMAGE_LAYOUT_UNDEFINED,
				KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
			);
		}
	}

	/**
	 * Presents the acquired image on the fixture's queue, optionally after a wait semaphore,
	 * returning the raw VkResult so the caller can assert on it.
	 *
	 * Routed through the interposer's exported present wrapper - the seam the DLSS-G plugin
	 * intercepts: the plugin reads the frame's tagged inputs and the presented backbuffer and
	 * generates the interpolated frame(s) before the real present is forwarded to the driver.
	 */
	public int present(final long swapchain, final int imageIndex, final long waitSemaphore) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack).sType$Default();
			if (waitSemaphore != VK10.VK_NULL_HANDLE) {
				presentInfo.pWaitSemaphores(stack.longs(waitSemaphore));
			}
			presentInfo.pSwapchains(stack.longs(swapchain));
			presentInfo.pImageIndices(stack.ints(imageIndex));
			return callInterposer(INTERPOSER_QUEUE_PRESENT, queue.address(), MemorySegment.ofAddress(presentInfo.address()));
		}
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
			// The swapchain and surface are device/instance children and must go before the
			// device and instance they belong to, and the window after the surface that
			// references it - the reverse of creation. The destroys are routed through the
			// interposer like the creates: its hook list names vkDestroySwapchainKHR and
			// vkDestroySurfaceKHR, and the DLSS-G plugin drops its swapchain/surface tracking
			// in those hooks - a destroy behind its back would leave it holding handles the
			// driver just freed.
			if (swapchain != VK10.VK_NULL_HANDLE) {
				callInterposerVoid(
					INTERPOSER_DESTROY_SWAPCHAIN,
					device.address(),
					swapchain,
					MemorySegment.NULL
				);
				swapchain = VK10.VK_NULL_HANDLE;
			}
			if (surface != VK10.VK_NULL_HANDLE) {
				callInterposerVoid(
					INTERPOSER_DESTROY_SURFACE,
					instance.address(),
					surface,
					MemorySegment.NULL
				);
				surface = VK10.VK_NULL_HANDLE;
			}
			for (long semaphore : ownedSemaphores) {
				VK10.vkDestroySemaphore(device, semaphore, null);
			}
			ownedSemaphores.clear();
			if (window != 0L) {
				final long hwnd = window;
				window = 0L;
				// The window is a plain Win32 object with no Vulkan dependency; a failure to
				// destroy it is a process-level leak the fixture cannot repair, so it is
				// attempted best-effort rather than allowed to mask a real close failure.
				try {
					DESTROY_WINDOW.invokeExact(MemorySegment.ofAddress(hwnd));
				} catch (Throwable ignored) {
				}
			}
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

	/*
	 * The hidden-window plumbing: a fixture-owned window class whose procedure forwards
	 * everything to DefWindowProcW, and a CreateWindowExW call sized to the presentation
	 * target. The window is never shown; it exists to give the WSI a real HWND to bind the
	 * surface to, which is what the driver and the Streamline interposer expect.
	 *
	 * The Win32 entry points are reached through the FFM linker rather than JNA or a custom
	 * windowing dependency: user32/kernel32 are already loaded in every JVM, the structs are
	 * small and stable, and the fixture owns its class registration so no other windowing
	 * stack is disturbed.
	 */
	private static final Linker WIN_LINKER = Linker.nativeLinker();
	private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32", Arena.global());
	private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", Arena.global());

	/** The name of the fixture-owned window class, registered once per process. */
	private static final String WINDOW_CLASS = "mc-dlss-headless-surface";
	/** WS_POPUP: no caption or frame, so the window size is its client size. */
	private static final int WS_POPUP = 0x80000000;
	/** WS_VISIBLE: the DLSS-G plugin matches swapchains to windows it can SEE (FindWindowExA + IsWindowVisible). */
	private static final int WS_VISIBLE = 0x10000000;
	/** The window is parked far off-screen: visible to the plugin, never seen by the user. */
	private static final int OFFSCREEN_POSITION = -32000;
	private static long windowModule;
	/** RegisterClassExW once per process: a second registration of the same class fails. */
	private static boolean windowClassRegistered;

	private static final MethodHandle GET_MODULE_HANDLE_W = WIN_LINKER.downcallHandle(
		KERNEL32.find("GetModuleHandleW").orElseThrow(() -> new IllegalStateException("kernel32 lacks GetModuleHandleW")),
		FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
	);
	private static final MethodHandle DESTROY_WINDOW = WIN_LINKER.downcallHandle(
		USER32.find("DestroyWindow").orElseThrow(() -> new IllegalStateException("user32 lacks DestroyWindow")),
		FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
	);
	private static final MethodHandle DEF_WINDOW_PROC_W = WIN_LINKER.downcallHandle(
		USER32.find("DefWindowProcW").orElseThrow(() -> new IllegalStateException("user32 lacks DefWindowProcW")),
		FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
	);
	private static final MethodHandle REGISTER_CLASS_EX_W = WIN_LINKER.downcallHandle(
		USER32.find("RegisterClassExW").orElseThrow(() -> new IllegalStateException("user32 lacks RegisterClassExW")),
		FunctionDescriptor.of(ValueLayout.JAVA_SHORT, ValueLayout.ADDRESS)
	);
	private static final MethodHandle CREATE_WINDOW_EX_W = WIN_LINKER.downcallHandle(
		USER32.find("CreateWindowExW").orElseThrow(() -> new IllegalStateException("user32 lacks CreateWindowExW")),
		FunctionDescriptor.of(
			ValueLayout.ADDRESS,
			ValueLayout.JAVA_INT, // dwExStyle
			ValueLayout.ADDRESS, // lpClassName
			ValueLayout.ADDRESS, // lpWindowName
			ValueLayout.JAVA_INT, // dwStyle
			ValueLayout.JAVA_INT, // X
			ValueLayout.JAVA_INT, // Y
			ValueLayout.JAVA_INT, // nWidth
			ValueLayout.JAVA_INT, // nHeight
			ValueLayout.ADDRESS, // hWndParent
			ValueLayout.ADDRESS, // hMenu
			ValueLayout.ADDRESS, // hInstance
			ValueLayout.ADDRESS // lpParam
		)
	);

	/**
	 * {@code WNDCLASSEXW}: 4+4+8+4+4 + 8*7 = 80 bytes, the 8-byte-aligned layout of the
	 * documented fields. Every handle field is an address; the class name is a wide string.
	 */
	private static final StructLayout WNDCLASSEX_LAYOUT = MemoryLayout.structLayout(
		ValueLayout.JAVA_INT.withName("cbSize"),
		ValueLayout.JAVA_INT.withName("style"),
		ValueLayout.ADDRESS.withName("lpfnWndProc"),
		ValueLayout.JAVA_INT.withName("cbClsExtra"),
		ValueLayout.JAVA_INT.withName("cbWndExtra"),
		ValueLayout.ADDRESS.withName("hInstance"),
		ValueLayout.ADDRESS.withName("hIcon"),
		ValueLayout.ADDRESS.withName("hCursor"),
		ValueLayout.ADDRESS.withName("hbrBackground"),
		ValueLayout.ADDRESS.withName("lpszMenuName"),
		ValueLayout.ADDRESS.withName("lpszClassName"),
		ValueLayout.ADDRESS.withName("hIconSm")
	).withName("WNDCLASSEXW");
	private static final VarHandle WNDCLASSEX_CB_SIZE = WNDCLASSEX_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("cbSize"));
	private static final VarHandle WNDCLASSEX_WND_PROC = WNDCLASSEX_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("lpfnWndProc"));
	private static final VarHandle WNDCLASSEX_HINSTANCE = WNDCLASSEX_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("hInstance"));
	private static final VarHandle WNDCLASSEX_CLASS_NAME = WNDCLASSEX_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("lpszClassName"));

	/** The window-procedure upcall stub, created once and pinned for the process lifetime. */
	private static final MemorySegment WINDOW_PROC_STUB = WIN_LINKER.upcallStub(
		windowProcHandle(),
		FunctionDescriptor.of(
			ValueLayout.JAVA_LONG,
			ValueLayout.ADDRESS, // hwnd
			ValueLayout.JAVA_INT, // message
			ValueLayout.JAVA_LONG, // wParam
			ValueLayout.JAVA_LONG // lParam
		),
		Arena.global()
	);

	/** The static target of the window-procedure upcall; the lookup cannot fail for a method this class declares. */
	private static MethodHandle windowProcHandle() {
		try {
			return MethodHandles.lookup().findStatic(
				HeadlessVulkanFixture.class,
				"windowProc",
				MethodType.methodType(long.class, MemorySegment.class, int.class, long.class, long.class)
			);
		} catch (ReflectiveOperationException error) {
			throw new ExceptionInInitializerError(error);
		}
	}

	/** Forwards every window message to the default procedure, like a stock window does. */
	private static long windowProc(
		final MemorySegment hwnd,
		final int message,
		final long wParam,
		final long lParam
	) {
		try {
			return (long)DEF_WINDOW_PROC_W.invokeExact(hwnd, message, wParam, lParam);
		} catch (Throwable error) {
			return 0L;
		}
	}

	/**
	 * Allocates a NUL-terminated UTF-16 (wide) string for the Win32 entry points, which take
	 * UTF-16 strings exclusively. The FFM allocator has no charset-aware string overload, so
	 * the wide characters are written explicitly with the terminator appended.
	 */
	private static MemorySegment wideString(final SegmentAllocator allocator, final String value) {
		final char[] chars = new char[value.length() + 1];
		value.getChars(0, value.length(), chars, 0);
		return allocator.allocateFrom(ValueLayout.JAVA_CHAR, chars);
	}

	private static long createHiddenWindow(final int width, final int height) {
		try (Arena arena = Arena.ofConfined()) {
			final long module = ((MemorySegment)GET_MODULE_HANDLE_W.invokeExact(MemorySegment.NULL)).address();
			windowModule = module;

			if (!windowClassRegistered) {
				final MemorySegment wndClass = arena.allocate(WNDCLASSEX_LAYOUT);
				WNDCLASSEX_CB_SIZE.set(wndClass, 0L, (int)WNDCLASSEX_LAYOUT.byteSize());
				WNDCLASSEX_WND_PROC.set(wndClass, 0L, WINDOW_PROC_STUB);
				WNDCLASSEX_HINSTANCE.set(wndClass, 0L, MemorySegment.ofAddress(module));
				WNDCLASSEX_CLASS_NAME.set(wndClass, 0L, wideString(arena, WINDOW_CLASS));
				final short atom = (short)REGISTER_CLASS_EX_W.invokeExact(wndClass);
				if (atom == 0) {
					throw new IllegalStateException("RegisterClassExW failed for " + WINDOW_CLASS);
				}
				windowClassRegistered = true;
			}

			final MemorySegment hwnd = (MemorySegment)CREATE_WINDOW_EX_W.invokeExact(
				0, // dwExStyle
				wideString(arena, WINDOW_CLASS),
				wideString(arena, "mc-dlss-headless-surface"),
				WS_POPUP | WS_VISIBLE, // no frame, client-sized, visible but parked off-screen
				OFFSCREEN_POSITION, // X
				OFFSCREEN_POSITION, // Y
				width, // nWidth
				height, // nHeight
				MemorySegment.NULL, // hWndParent
				MemorySegment.NULL, // hMenu
				MemorySegment.ofAddress(module), // hInstance
				MemorySegment.NULL // lpParam
			);
			if (hwnd.address() == 0L) {
				throw new IllegalStateException("CreateWindowExW failed");
			}
			return hwnd.address();
		} catch (IllegalStateException error) {
			throw error;
		} catch (Throwable error) {
			throw new IllegalStateException("Failed to create the hidden surface window", error);
		}
	}

	private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";
	private static final long FENCE_TIMEOUT_NANOSECONDS = 10_000_000_000L;

	/*
	 * The interposer routing for the present chain (createSwapchainKHR, getSwapchainImagesKHR,
	 * acquireNextImageKHR, queuePresentKHR). sl.interposer.dll is loaded into the process by
	 * the Streamline bootstrap (Native.open / ExtensionBootstrap load it beside the staged
	 * runtime), and its exported Vulkan wrappers fire the plugin manager's hooks for the
	 * functions the DLSS-G plugin needs to see, then forward to the driver. The fixture
	 * resolves the wrappers lazily so a test that never presents does not depend on the
	 * staged runtime.
	 *
	 * ponytail: these five duplicate StreamlineVulkanProxies, which production now routes
	 * through. That duplication is what let the fixture prove a present chain production never
	 * took - every FG rung passed while the game reported presented=0. Collapse onto the shared
	 * class (keeping the two surface wrappers below, which it does not cover) once the in-game
	 * present chain is verified, so a future divergence cannot hide the same way.
	 */
	private static final MethodHandle INTERPOSER_CREATE_SWAPCHAIN = bindInterposer(
		"vkCreateSwapchainKHR",
		FunctionDescriptor.of(
			ValueLayout.JAVA_INT,
			ValueLayout.JAVA_LONG, // device
			ValueLayout.ADDRESS, // pCreateInfo
			ValueLayout.ADDRESS, // pAllocator
			ValueLayout.ADDRESS // pSwapchain
		)
	);
	private static final MethodHandle INTERPOSER_GET_SWAPCHAIN_IMAGES = bindInterposer(
		"vkGetSwapchainImagesKHR",
		FunctionDescriptor.of(
			ValueLayout.JAVA_INT,
			ValueLayout.JAVA_LONG, // device
			ValueLayout.JAVA_LONG, // swapchain
			ValueLayout.ADDRESS, // pSwapchainImageCount
			ValueLayout.ADDRESS // pSwapchainImages
		)
	);
	private static final MethodHandle INTERPOSER_ACQUIRE_NEXT_IMAGE = bindInterposer(
		"vkAcquireNextImageKHR",
		FunctionDescriptor.of(
			ValueLayout.JAVA_INT,
			ValueLayout.JAVA_LONG, // device
			ValueLayout.JAVA_LONG, // swapchain
			ValueLayout.JAVA_LONG, // timeout
			ValueLayout.JAVA_LONG, // semaphore
			ValueLayout.JAVA_LONG, // fence
			ValueLayout.ADDRESS // pImageIndex
		)
	);
	private static final MethodHandle INTERPOSER_QUEUE_PRESENT = bindInterposer(
		"vkQueuePresentKHR",
		FunctionDescriptor.of(
			ValueLayout.JAVA_INT,
			ValueLayout.JAVA_LONG, // queue
			ValueLayout.ADDRESS // pPresentInfo
		)
	);
	private static final MethodHandle INTERPOSER_CREATE_WIN32_SURFACE = bindInterposer(
		"vkCreateWin32SurfaceKHR",
		FunctionDescriptor.of(
			ValueLayout.JAVA_INT,
			ValueLayout.JAVA_LONG, // instance
			ValueLayout.ADDRESS, // pCreateInfo
			ValueLayout.ADDRESS, // pAllocator
			ValueLayout.ADDRESS // pSurface
		)
	);
	private static final MethodHandle INTERPOSER_DESTROY_SURFACE = bindInterposer(
		"vkDestroySurfaceKHR",
		FunctionDescriptor.ofVoid(
			ValueLayout.JAVA_LONG, // instance
			ValueLayout.JAVA_LONG, // surface
			ValueLayout.ADDRESS // pAllocator
		)
	);
	private static final MethodHandle INTERPOSER_DESTROY_SWAPCHAIN = bindInterposer(
		"vkDestroySwapchainKHR",
		FunctionDescriptor.ofVoid(
			ValueLayout.JAVA_LONG, // device
			ValueLayout.JAVA_LONG, // swapchain
			ValueLayout.ADDRESS // pAllocator
		)
	);

	/**
	 * Resolves one of the interposer's exported Vulkan wrappers for a downcall. The
	 * interposer is already loaded by the Streamline bootstrap, so the library lookup finds
	 * the same module; the lookup is pinned for the process like the bridge's own.
	 */
	private static MethodHandle bindInterposer(final String name, final FunctionDescriptor descriptor) {
		final Path interposer = ExtensionBootstrap.streamlineRuntimeDirectory().resolve("sl.interposer.dll");
		final SymbolLookup lookup = SymbolLookup.libraryLookup(interposer, Arena.global());
		return Linker.nativeLinker().downcallHandle(
			lookup.find(name).orElseThrow(() -> new IllegalStateException("sl.interposer.dll lacks " + name)),
			descriptor
		);
	}

	/**
	 * Invokes one of the interposer wrappers and returns its VkResult, unwrapping the
	 * invocation failure into the same loud shape every other fixture call has.
	 */
	private static int callInterposer(final MethodHandle function, final Object... arguments) {
		try {
			return (int)function.invokeWithArguments(arguments);
		} catch (Throwable error) {
			throw new IllegalStateException("Interposer call through " + function + " failed", error);
		}
	}

	/**
	 * Invokes one of the interposer wrappers that returns void (the destroy hooks), with
	 * the same unwrapping discipline as {@link #callInterposer}.
	 */
	private static void callInterposerVoid(final MethodHandle function, final Object... arguments) {
		try {
			function.invokeWithArguments(arguments);
		} catch (Throwable error) {
			throw new IllegalStateException("Interposer call through " + function + " failed", error);
		}
	}
}
