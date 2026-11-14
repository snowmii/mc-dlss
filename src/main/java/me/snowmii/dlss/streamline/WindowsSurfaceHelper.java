package me.snowmii.dlss.streamline;

import me.snowmii.streamline.StreamlineVulkanProxies;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.windows.WindowsLibrary;
import org.lwjgl.vulkan.VkWin32SurfaceCreateInfoKHR;

public final class WindowsSurfaceHelper {
	private WindowsSurfaceHelper() {
	}

	public static int createSurface(
		final long instance,
		final long hwnd,
		final long allocator,
		final long pSurface
	) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			final VkWin32SurfaceCreateInfoKHR createInfo = VkWin32SurfaceCreateInfoKHR.calloc(stack)
				.sType$Default()
				.pNext(0L)
				.flags(0)
				.hinstance(WindowsLibrary.HINSTANCE)
				.hwnd(hwnd);
			return StreamlineVulkanProxies.createWin32Surface(instance, createInfo.address(), allocator, pSurface);
		}
	}
}
