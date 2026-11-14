package me.snowmii.streamline;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamlineVulkanProxiesTest {
	private static long[] observed;

	@Test
	void exactCallsPreserveAllSevenProxySignaturesAndArguments() throws Throwable {
		assertEquals(21, StreamlineVulkanProxies.invokeCreateSwapchain(
				target("createSwapchain", int.class, long.class, MemorySegment.class, MemorySegment.class, MemorySegment.class),
				1L, 0L, 2L, 3L));
		assertArrayEquals(new long[]{1L, 0L, 2L, 3L}, observed);

		assertEquals(22, StreamlineVulkanProxies.invokeGetSwapchainImages(
				target("getSwapchainImages", int.class, long.class, long.class, MemorySegment.class, MemorySegment.class),
				4L, 5L, 6L, 7L));
		assertArrayEquals(new long[]{4L, 5L, 6L, 7L}, observed);

		assertEquals(23, StreamlineVulkanProxies.invokeAcquireNextImage(
				target("acquireNextImage", int.class, long.class, long.class, long.class, long.class, long.class,
						MemorySegment.class),
				8L, 9L, 10L, 11L, 12L, 13L));
		assertArrayEquals(new long[]{8L, 9L, 10L, 11L, 12L, 13L}, observed);

		assertEquals(24, StreamlineVulkanProxies.invokeQueuePresent(
				target("queuePresent", int.class, long.class, MemorySegment.class), 14L, 15L));
		assertArrayEquals(new long[]{14L, 15L}, observed);

		StreamlineVulkanProxies.invokeDestroySwapchain(
				target("destroySwapchain", void.class, long.class, long.class, MemorySegment.class), 16L, 17L, 18L);
		assertArrayEquals(new long[]{16L, 17L, 18L}, observed);

		assertEquals(25, StreamlineVulkanProxies.invokeCreateWin32Surface(
				target("createWin32Surface", int.class, long.class, MemorySegment.class,
						MemorySegment.class, MemorySegment.class), 19L, 20L, 21L, 22L));
		assertArrayEquals(new long[]{19L, 20L, 21L, 22L}, observed);

		StreamlineVulkanProxies.invokeDestroySurface(
				target("destroySurface", void.class, long.class, long.class, MemorySegment.class), 23L, 24L, 25L);
		assertArrayEquals(new long[]{23L, 24L, 25L}, observed);
	}

	private static MethodHandle target(String name, Class<?> result, Class<?>... parameters) throws ReflectiveOperationException {
		return MethodHandles.lookup().findStatic(StreamlineVulkanProxiesTest.class, name,
				MethodType.methodType(result, parameters));
	}

	private static int createSwapchain(long device, MemorySegment createInfo, MemorySegment allocator, MemorySegment output) {
		observed = new long[]{device, createInfo.address(), allocator.address(), output.address()};
		return 21;
	}

	private static int getSwapchainImages(long device, long swapchain, MemorySegment count, MemorySegment images) {
		observed = new long[]{device, swapchain, count.address(), images.address()};
		return 22;
	}

	private static int acquireNextImage(long device, long swapchain, long timeout, long semaphore, long fence,
			MemorySegment imageIndex) {
		observed = new long[]{device, swapchain, timeout, semaphore, fence, imageIndex.address()};
		return 23;
	}

	private static int queuePresent(long queue, MemorySegment presentInfo) {
		observed = new long[]{queue, presentInfo.address()};
		return 24;
	}

	private static void destroySwapchain(long device, long swapchain, MemorySegment allocator) {
		observed = new long[]{device, swapchain, allocator.address()};
	}

	private static int createWin32Surface(long instance, MemorySegment createInfo, MemorySegment allocator,
			MemorySegment surface) {
		observed = new long[]{instance, createInfo.address(), allocator.address(), surface.address()};
		return 25;
	}

	private static void destroySurface(long instance, long surface, MemorySegment allocator) {
		observed = new long[]{instance, surface, allocator.address()};
	}
}
