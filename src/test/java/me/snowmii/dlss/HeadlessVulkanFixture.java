package me.snowmii.dlss;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

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

	public HeadlessVulkanFixture() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
				.sType$Default()
				.pApplicationName(stack.UTF8("mc-dlss-vulkan-context-test"))
				.applicationVersion(1)
				.pEngineName(stack.UTF8("mc-dlss"))
				.engineVersion(1)
				.apiVersion(VK10.VK_API_VERSION_1_0);

			VkInstanceCreateInfo instanceInfo = VkInstanceCreateInfo.calloc(stack)
				.sType$Default()
				.pApplicationInfo(appInfo);

			PointerBuffer instancePtr = stack.callocPointer(1);
			checkVk(VK10.vkCreateInstance(instanceInfo, null, instancePtr), "vkCreateInstance");
			instance = new VkInstance(instancePtr.get(0), instanceInfo);

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

			// queueCount derives from pQueuePriorities.remaining(); do NOT flip().
			VkDeviceQueueCreateInfo.Buffer queueCreateInfo = VkDeviceQueueCreateInfo.calloc(1, stack);
			queueCreateInfo.get(0).sType$Default();
			queueCreateInfo.get(0).queueFamilyIndex(queueFamilyIndex);
			queueCreateInfo.get(0).pQueuePriorities(stack.callocFloat(1).put(0, 1.0f));

			VkDeviceCreateInfo deviceCreateInfo = VkDeviceCreateInfo.calloc(stack)
				.sType$Default()
				.pQueueCreateInfos(queueCreateInfo);

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

	@Override
	public void close() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			if (!allocatedCommandBuffers.isEmpty()) {
				PointerBuffer buffers = stack.callocPointer(allocatedCommandBuffers.size());
				for (int i = 0; i < allocatedCommandBuffers.size(); i++) {
					buffers.put(i, allocatedCommandBuffers.get(i));
				}
				VK10.vkFreeCommandBuffers(device, commandPool, buffers);
			}
			VK10.vkDestroyCommandPool(device, commandPool, null);
			VK10.vkDestroyDevice(device, null);
			VK10.vkDestroyInstance(instance, null);
		}
	}

	private static void checkVk(int result, String call) {
		if (result != VK10.VK_SUCCESS) {
			throw new IllegalStateException(call + " failed with VkResult " + result);
		}
	}
}
