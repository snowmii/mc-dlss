package me.snowmii.dlss.bridge;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import me.snowmii.streamline.ExtensionBootstrap;

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
 * remove. The mod's own live rungs passed only because the test fixture routed through these
 * wrappers while production did not.
 *
 * <p>The five functions here are the ones {@code sl_hooks.h} lists for Vulkan. Routing is
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
		return Handles.BOUND;
	}

	/** {@code vkCreateSwapchainKHR}: the call that lets DLSS-G size and own the swapchain. */
	public static int createSwapchain(
		final long device, final long createInfo, final long allocator, final long swapchain
	) {
		return call(Handles.CREATE_SWAPCHAIN, device, segment(createInfo), segment(allocator), segment(swapchain));
	}

	/** {@code vkGetSwapchainImagesKHR}: enumerates the images DLSS-G may have replaced. */
	public static int getSwapchainImages(
		final long device, final long swapchain, final long imageCount, final long images
	) {
		return call(Handles.GET_SWAPCHAIN_IMAGES, device, swapchain, segment(imageCount), segment(images));
	}

	/** {@code vkAcquireNextImageKHR}. */
	public static int acquireNextImage(
		final long device, final long swapchain, final long timeout,
		final long semaphore, final long fence, final long imageIndex
	) {
		return call(Handles.ACQUIRE_NEXT_IMAGE, device, swapchain, timeout, semaphore, fence, segment(imageIndex));
	}

	/** {@code vkQueuePresentKHR}: the seam that drives generation and runs {@code presentCommon()}. */
	public static int queuePresent(final long queue, final long presentInfo) {
		return call(Handles.QUEUE_PRESENT, queue, segment(presentInfo));
	}

	/** {@code vkDestroySwapchainKHR}: lets DLSS-G release what it allocated against the swapchain. */
	public static void destroySwapchain(final long device, final long swapchain, final long allocator) {
		try {
			Handles.DESTROY_SWAPCHAIN.invokeExact(device, swapchain, segment(allocator));
		} catch (Throwable error) {
			throw new IllegalStateException("Streamline vkDestroySwapchainKHR proxy failed", error);
		}
	}

	/** A raw Vulkan pointer as a segment; zero is Vulkan's null (an absent allocator or out-buffer). */
	private static MemorySegment segment(final long address) {
		return address == 0L ? MemorySegment.NULL : MemorySegment.ofAddress(address);
	}

	private static int call(final MethodHandle function, final Object... arguments) {
		try {
			return (int)function.invokeWithArguments(arguments);
		} catch (Throwable error) {
			throw new IllegalStateException("Streamline present-chain proxy failed", error);
		}
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
		private static final boolean BOUND;

		static {
			MethodHandle createSwapchain = null;
			MethodHandle getSwapchainImages = null;
			MethodHandle acquireNextImage = null;
			MethodHandle queuePresent = null;
			MethodHandle destroySwapchain = null;
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
				));
				getSwapchainImages = bind(lookup, "vkGetSwapchainImagesKHR", FunctionDescriptor.of(
					ValueLayout.JAVA_INT,
					ValueLayout.JAVA_LONG, // device
					ValueLayout.JAVA_LONG, // swapchain
					ValueLayout.ADDRESS, // pSwapchainImageCount
					ValueLayout.ADDRESS // pSwapchainImages
				));
				acquireNextImage = bind(lookup, "vkAcquireNextImageKHR", FunctionDescriptor.of(
					ValueLayout.JAVA_INT,
					ValueLayout.JAVA_LONG, // device
					ValueLayout.JAVA_LONG, // swapchain
					ValueLayout.JAVA_LONG, // timeout
					ValueLayout.JAVA_LONG, // semaphore
					ValueLayout.JAVA_LONG, // fence
					ValueLayout.ADDRESS // pImageIndex
				));
				queuePresent = bind(lookup, "vkQueuePresentKHR", FunctionDescriptor.of(
					ValueLayout.JAVA_INT,
					ValueLayout.JAVA_LONG, // queue
					ValueLayout.ADDRESS // pPresentInfo
				));
				destroySwapchain = bind(lookup, "vkDestroySwapchainKHR", FunctionDescriptor.ofVoid(
					ValueLayout.JAVA_LONG, // device
					ValueLayout.JAVA_LONG, // swapchain
					ValueLayout.ADDRESS // pAllocator
				));
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
			BOUND = bound;
		}

		private static MethodHandle bind(
			final SymbolLookup lookup, final String name, final FunctionDescriptor descriptor
		) {
			return Linker.nativeLinker().downcallHandle(
				lookup.find(name).orElseThrow(() -> new IllegalStateException("sl.interposer.dll lacks " + name)),
				descriptor
			);
		}
	}
}
