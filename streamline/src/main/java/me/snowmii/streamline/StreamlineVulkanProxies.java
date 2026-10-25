package me.snowmii.streamline;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.nio.file.Path;

/**
 * The Streamline present-chain proxies, bound from {@code sl.interposer.dll}'s exported Vulkan
 * wrappers.
 *
 * <p>Under {@code PreferenceFlags::eUseManualHooking} - the flag this mod initializes Streamline
 * with - SL installs no global interception. The host must fetch the proxies for the mandatory
 * present-chain entry points itself and call those instead of the driver's, exactly as
 * {@code ProgrammingGuideManualHooking.md} section 4 prescribes. The wrappers fire the plugin
 * manager's hooks (which is how DLSS-G ever sees a swapchain, and how the common plugin's
 * {@code presentCommon()} runs) and then forward to the driver.
 *
 * <p>Calling the driver's functions directly instead is silent: rendering works, and DLSS-G
 * simply never generates. It reports {@code presented=0 status=0 fence=0} while sl.log carries
 * "Streamline presentCommon() was not observed" - the exact in-game symptom this class exists to
 * remove. Tests and production both route through these wrappers.
 *
 * <p>The seven functions here are the ones {@code sl_hooks.h} lists for Vulkan. Routing is
 * unconditional whenever the staged runtime is present, mirroring the unconditional
 * {@code slInit} at the instance seam: the guide requires {@code presentCommon()} every frame,
 * not only while frame generation is on. When the staged runtime is missing, {@link #available()}
 * answers false and every caller falls back to the driver's own function.
 */
public final class StreamlineVulkanProxies {
	private StreamlineVulkanProxies() {
	}

	/**
	 * Whether the proxies bound. False leaves callers on their vanilla path, so a missing or
	 * unloadable staged runtime degrades to "no frame generation" rather than "no rendering".
	 */
	public static boolean available() {
		return Handles.BOUND && !Boolean.getBoolean("mc.dlss.disable-vulkan-proxies");
	}

	/** {@code vkCreateSwapchainKHR}: the call that lets DLSS-G size and own the swapchain. */
	public static int createSwapchain(
		final long device, final long createInfo, final long allocator, final long swapchain
	) {
		return invokeCreateSwapchain(Handles.CREATE_SWAPCHAIN, device, createInfo, allocator, swapchain);
	}

	/** {@code vkGetSwapchainImagesKHR}: enumerates the images DLSS-G may have replaced. */
	public static int getSwapchainImages(
		final long device, final long swapchain, final long imageCount, final long images
	) {
		return invokeGetSwapchainImages(Handles.GET_SWAPCHAIN_IMAGES, device, swapchain, imageCount, images);
	}

	/** {@code vkAcquireNextImageKHR}. */
	public static int acquireNextImage(
		final long device, final long swapchain, final long timeout,
		final long semaphore, final long fence, final long imageIndex
	) {
		return invokeAcquireNextImage(Handles.ACQUIRE_NEXT_IMAGE, device, swapchain, timeout, semaphore, fence, imageIndex);
	}

	/** {@code vkQueuePresentKHR}: the seam that drives generation and runs {@code presentCommon()}. */
	public static int queuePresent(final long queue, final long presentInfo) {
		return invokeQueuePresent(Handles.QUEUE_PRESENT, queue, presentInfo);
	}

	/** {@code vkDestroySwapchainKHR}: lets DLSS-G release what it allocated against the swapchain. */
	public static void destroySwapchain(final long device, final long swapchain, final long allocator) {
		try {
			invokeDestroySwapchain(Handles.DESTROY_SWAPCHAIN, device, swapchain, allocator);
		} catch (Throwable error) {
			throw new IllegalStateException("Streamline vkDestroySwapchainKHR proxy failed", error);
		}
	}

	/** {@code vkCreateWin32SurfaceKHR}: registers the surface-to-HWND mapping with Streamline. */
	public static int createWin32Surface(
		final long instance, final long createInfo, final long allocator, final long surface
	) {
		return invokeCreateWin32Surface(Handles.CREATE_WIN32_SURFACE, instance, createInfo, allocator, surface);
	}

	/** {@code vkDestroySurfaceKHR}: unregisters the surface from Streamline. */
	public static void destroySurface(final long instance, final long surface, final long allocator) {
		try {
			invokeDestroySurface(Handles.DESTROY_SURFACE, instance, surface, allocator);
		} catch (Throwable error) {
			throw new IllegalStateException("Streamline vkDestroySurfaceKHR proxy failed", error);
		}
	}

	/** A raw Vulkan pointer as a segment; zero is Vulkan's null (an absent allocator or out-buffer). */
	private static MemorySegment segment(final long address) {
		return address == 0L ? MemorySegment.NULL : MemorySegment.ofAddress(address);
	}

	/* Keep each call site's static types identical to its FunctionDescriptor. The generic
	 * invokeWithArguments path boxes primitive handles and constructs an Object[] per call.
	 */
	static int invokeCreateSwapchain(
			final MethodHandle function, final long device, final long createInfo,
			final long allocator, final long swapchain
	) {
		try {
			return (int)function.invokeExact(
					device, segment(createInfo), segment(allocator), segment(swapchain));
		} catch (Throwable error) {
			throw new IllegalStateException("Streamline present-chain proxy failed", error);
		}
	}

	static int invokeGetSwapchainImages(
			final MethodHandle function, final long device, final long swapchain,
			final long imageCount, final long images
	) {
		try {
			return (int)function.invokeExact(device, swapchain, segment(imageCount), segment(images));
		} catch (Throwable error) {
			throw new IllegalStateException("Streamline present-chain proxy failed", error);
		}
	}

	static int invokeAcquireNextImage(
			final MethodHandle function, final long device, final long swapchain, final long timeout,
			final long semaphore, final long fence, final long imageIndex
	) {
		try {
			return (int)function.invokeExact(
					device, swapchain, timeout, semaphore, fence, segment(imageIndex));
		} catch (Throwable error) {
			throw new IllegalStateException("Streamline present-chain proxy failed", error);
		}
	}

	static int invokeQueuePresent(final MethodHandle function, final long queue, final long presentInfo) {
		try {
			return (int)function.invokeExact(queue, segment(presentInfo));
		} catch (Throwable error) {
			throw new IllegalStateException("Streamline present-chain proxy failed", error);
		}
	}

	static void invokeDestroySwapchain(
			final MethodHandle function, final long device, final long swapchain, final long allocator
	) throws Throwable {
		function.invokeExact(device, swapchain, segment(allocator));
	}

	static int invokeCreateWin32Surface(
			final MethodHandle function, final long instance, final long createInfo,
			final long allocator, final long surface
	) {
		try {
			return (int)function.invokeExact(
					instance, segment(createInfo), segment(allocator), segment(surface));
		} catch (Throwable error) {
			throw new IllegalStateException("Streamline present-chain proxy failed", error);
		}
	}

	static void invokeDestroySurface(
			final MethodHandle function, final long instance, final long surface, final long allocator
	) throws Throwable {
		function.invokeExact(instance, surface, segment(allocator));
	}

	/**
	 * Lazily bound so a session that never renders - and never stages the runtime - does not pay
	 * for the lookup or fail on it. Binding failure is recorded once as {@link #BOUND} false.
	 */
	private static final class Handles {
		private static final MethodHandle CREATE_SWAPCHAIN;
		private static final MethodHandle GET_SWAPCHAIN_IMAGES;
		private static final MethodHandle ACQUIRE_NEXT_IMAGE;
		private static final MethodHandle QUEUE_PRESENT;
		private static final MethodHandle DESTROY_SWAPCHAIN;
		private static final MethodHandle CREATE_WIN32_SURFACE;
		private static final MethodHandle DESTROY_SURFACE;
		private static final boolean BOUND;

		static {
			MethodHandle createSwapchain = null;
			MethodHandle getSwapchainImages = null;
			MethodHandle acquireNextImage = null;
			MethodHandle queuePresent = null;
			MethodHandle destroySwapchain = null;
			MethodHandle createWin32Surface = null;
			MethodHandle destroySurface = null;
			boolean bound = false;
			try {
				final Path interposer =
					ExtensionBootstrap.streamlineRuntimeDirectory().resolve("sl.interposer.dll");
				final SymbolLookup lookup = SymbolLookup.libraryLookup(interposer, Arena.global());
				createSwapchain = bind(lookup, "vkCreateSwapchainKHR", FunctionDescriptor.of(
					ValueLayout.JAVA_INT,
					ValueLayout.JAVA_LONG, // device
					ValueLayout.ADDRESS, // pCreateInfo
					ValueLayout.ADDRESS, // pAllocator
					ValueLayout.ADDRESS // pSwapchain
				), MethodType.methodType(int.class, long.class,
						MemorySegment.class, MemorySegment.class, MemorySegment.class));
				getSwapchainImages = bind(lookup, "vkGetSwapchainImagesKHR", FunctionDescriptor.of(
					ValueLayout.JAVA_INT,
					ValueLayout.JAVA_LONG, // device
					ValueLayout.JAVA_LONG, // swapchain
					ValueLayout.ADDRESS, // pSwapchainImageCount
					ValueLayout.ADDRESS // pSwapchainImages
				), MethodType.methodType(int.class, long.class, long.class,
						MemorySegment.class, MemorySegment.class));
				acquireNextImage = bind(lookup, "vkAcquireNextImageKHR", FunctionDescriptor.of(
					ValueLayout.JAVA_INT,
					ValueLayout.JAVA_LONG, // device
					ValueLayout.JAVA_LONG, // swapchain
					ValueLayout.JAVA_LONG, // timeout
					ValueLayout.JAVA_LONG, // semaphore
					ValueLayout.JAVA_LONG, // fence
					ValueLayout.ADDRESS // pImageIndex
				), MethodType.methodType(int.class, long.class, long.class, long.class,
						long.class, long.class, MemorySegment.class));
				queuePresent = bind(lookup, "vkQueuePresentKHR", FunctionDescriptor.of(
					ValueLayout.JAVA_INT,
					ValueLayout.JAVA_LONG, // queue
					ValueLayout.ADDRESS // pPresentInfo
				), MethodType.methodType(int.class, long.class, MemorySegment.class));
				destroySwapchain = bind(lookup, "vkDestroySwapchainKHR", FunctionDescriptor.ofVoid(
					ValueLayout.JAVA_LONG, // device
					ValueLayout.JAVA_LONG, // swapchain
					ValueLayout.ADDRESS // pAllocator
				), MethodType.methodType(void.class, long.class, long.class, MemorySegment.class));
				createWin32Surface = bind(lookup, "vkCreateWin32SurfaceKHR", FunctionDescriptor.of(
					ValueLayout.JAVA_INT,
					ValueLayout.JAVA_LONG, // instance
					ValueLayout.ADDRESS, // pCreateInfo
					ValueLayout.ADDRESS, // pAllocator
					ValueLayout.ADDRESS // pSurface
				), MethodType.methodType(int.class, long.class,
						MemorySegment.class, MemorySegment.class, MemorySegment.class));
				destroySurface = bind(lookup, "vkDestroySurfaceKHR", FunctionDescriptor.ofVoid(
					ValueLayout.JAVA_LONG, // instance
					ValueLayout.JAVA_LONG, // surface
					ValueLayout.ADDRESS // pAllocator
				), MethodType.methodType(void.class, long.class, long.class, MemorySegment.class));
				bound = true;
			} catch (Throwable ignored) {
				// A missing or unloadable staged runtime is not fatal: the callers keep their
				// vanilla path and the session runs without frame generation.
			}
			CREATE_SWAPCHAIN = createSwapchain;
			GET_SWAPCHAIN_IMAGES = getSwapchainImages;
			ACQUIRE_NEXT_IMAGE = acquireNextImage;
			QUEUE_PRESENT = queuePresent;
			DESTROY_SWAPCHAIN = destroySwapchain;
			CREATE_WIN32_SURFACE = createWin32Surface;
			DESTROY_SURFACE = destroySurface;
			BOUND = bound;
		}

		private static MethodHandle bind(
				final SymbolLookup lookup, final String name, final FunctionDescriptor descriptor,
				final MethodType expectedType
		) {
			final MethodHandle handle = Linker.nativeLinker().downcallHandle(
				lookup.find(name).orElseThrow(() -> new IllegalStateException("sl.interposer.dll lacks " + name)),
				descriptor
			);
			if (!handle.type().equals(expectedType)) {
				throw new IllegalStateException(
						"Unexpected method type for " + name + ": expected " + expectedType + " but got " + handle.type());
			}
			return handle;
		}
	}
}
