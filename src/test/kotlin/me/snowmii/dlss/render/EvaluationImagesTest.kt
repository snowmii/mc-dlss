package me.snowmii.dlss.render
import me.snowmii.streamline.ExtensionBootstrap
import me.snowmii.streamline.Native
import me.snowmii.streamline.NativeApi
import me.snowmii.streamline.NativeException
import me.snowmii.dlss.bridge.HeadlessVulkanFixture
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.SRMode
import me.snowmii.streamline.Dimensions
import me.snowmii.dlss.session.DlssSessionState
import me.snowmii.dlss.session.LifecycleAdapter

import java.nio.file.Files
import java.nio.file.Path
import me.snowmii.dlss.NativeBridge
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Proves the native bridge owns the two evaluation images DLSS needs, on the real Vulkan device.
 *
 * `mc_dlss_evaluate` takes motion and output resources as arguments and there is nowhere in the
 * repository for those handles to come from: Minecraft has a colour and a depth target for the
 * world, and no equivalent of either of these. Allocation is therefore native, sized from the
 * dimensions the last `mc_dlss_configure` stored, so nothing can disagree with the size the DLSS
 * feature is created at.
 *
 * The handles are only meaningful against a real device, which is why this runs through the same
 * headless Vulkan fixture: an image handle allocated against a real driver is either
 * genuinely there or the call failed.
 *
 * Teardown order matters: native release must complete before Vulkan destruction, so the native
 * handle closes (inner) before the fixture closes (outer).
 */
@NativeBridge
class EvaluationImagesTest {
	private val output = Dimensions(2560, 1440)

	@Test
	fun `configured dimensions produce reusable native motion and output images`(@TempDir dataPath: Path) {
		withReadySession(dataPath) { native, session, adapter, render ->
			val images = adapter.acquireImages()

			assertNotNull(images, session.failure?.diagnostic())
			assertTrue(images!!.motion.image != 0L, "motion image must be a real VkImage")
			assertTrue(images.motion.view != 0L, "motion image must carry a view")
			assertTrue(images.output.image != 0L, "output image must be a real VkImage")
			assertTrue(images.output.view != 0L, "output image must carry a view")
			assertNotEquals(images.motion.image, images.output.image)
			assertNotEquals(images.motion.view, images.output.view)
			// R16G16_SFLOAT for two-channel screen-space motion, R8G8B8A8_UNORM to match the
			// main target the output is copied into. Both are mandatory storage formats.
			assertEquals(VK_FORMAT_R16G16_SFLOAT, images.motion.format)
			assertEquals(VK_FORMAT_R8G8B8A8_UNORM, images.output.format)

			// A second acquire against unchanged configuration must hand back the same
			// allocation; anything else leaks one set of images per frame.
			assertEquals(images, adapter.acquireImages())
			assertNull(session.failure)

			// The images belong to the configured pair, not to the caller's expectations.
			assertTrue(render.width <= output.width && render.height <= output.height)
			assertTrue(adapter.releaseImages())
			// Releasing is idempotent, and acquiring again rebuilds them.
			assertTrue(adapter.releaseImages())
			val rebuilt = adapter.acquireImages()
			assertNotNull(rebuilt, session.failure?.diagnostic())
			assertTrue(rebuilt!!.motion.view != 0L)
			assertTrue(adapter.releaseImages())

			// A configuration change replaces the images rather than reusing the old size.
			// This lives in the same live session as the reuse assertions above: Streamline
			// accepts exactly one Vulkan device per process, so a second test method could not
			// activate a second device in this fork.
			val first = adapter.acquireImages()
			assertNotNull(first, session.failure?.diagnostic())

			// Half the render width is still a legal DLSS configuration for this output size,
			// and it is a different allocation from the one the quality mode produced.
			val reconfigured = native.configureSuperResolution(
				output.width,
				output.height,
				render.width / 2,
				render.height / 2,
				SRMode.PERFORMANCE.sdkValue,
				SRMode.PERFORMANCE.defaultPreset.sdkValue,
			)
			assertEquals(NativeApi.SUCCESS_RESULT, reconfigured)

			val second = adapter.acquireImages()
			assertNotNull(second, session.failure?.diagnostic())
			assertNotEquals(first!!.motion.view, second!!.motion.view)
			assertTrue(adapter.releaseImages())
		}
	}

	@Test
	fun `acquiring before the bridge is initialized allocates nothing`() {
		val library = nativeLibrary()
		Native.open(library).use { native ->
			val failure = runCatching { native.acquireImages() }.exceptionOrNull()

			assertTrue(
				failure is NativeException,
				"an uninitialized bridge must refuse rather than allocate: $failure",
			)
			assertEquals("acquire-images", (failure as NativeException).stage())
			// Releasing what was never acquired is still success, so teardown never has to ask.
			assertEquals(NativeApi.SUCCESS_RESULT, native.releaseImages())
		}
	}

	/**
	 * Drives the production path up to READY on the real device, then hands the caller the
	 * bridge, session, adapter, and Streamline-queried render dimensions.
	 *
	 * The device holds the merged queue layout and the proxies are activated, because the
	 * optimal-dimension query now answers from Streamline and requires the recorded device.
	 */
	private fun withReadySession(
		dataPath: Path,
		block: (Native, DlssSession, LifecycleAdapter, Dimensions) -> Unit,
	) {
		val library = nativeLibrary()
		val instanceExtensions = ExtensionBootstrap.queryInstanceExtensions()
		val requirements = Native.open(library).use { bridge ->
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				bridge.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
			)
			bridge.queryQueueRequirements()
		}
		val graphicsFamily = probeGraphicsQueueFamily()
		val extras = requirements.graphicsQueues + requirements.computeQueues

		HeadlessVulkanFixture(
			instanceExtensions,
			{ vkInstance, vkPhysicalDevice ->
				val extensions = mutableListOf<String>()
				ExtensionBootstrap.addDeviceExtensions(extensions, vkInstance, vkPhysicalDevice)
				extensions
			},
			false,
			mapOf(graphicsFamily to extras),
		).use { vulkan ->
			Native.open(library).use { native ->
				// Bootstrap is idempotent across bridge instances: the runtime is already up.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					native.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
				)
				// The fixture creates one host queue in the family, so Streamline's own queues
				// start at index 1 - right after the host's, as slSetVulkanInfo records them.
				val hostQueueCount = 1
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					native.activateVulkanProxies(
						vulkan.instanceAddress(),
					vulkan.physicalDeviceAddress(),
					vulkan.deviceAddress(),
					graphicsFamily,
					hostQueueCount,
					graphicsFamily,
					hostQueueCount,
				),
					"SL proxy activation must succeed against the merged queue layout",
				)
				val session = DlssSession(
					DlssStartupConfig(
						enabled = true,
						qualityMode = SRMode.QUALITY,
						outputDimensions = output,
						sdkPath = dataPath,
						nativeLibraryPath = library,
						dataPath = dataPath,
						warnings = emptyList(),
					),
				)
				val adapter = LifecycleAdapter(session, native)
				val render = adapter.initialize(
					vulkan.instanceAddress(),
					vulkan.physicalDeviceAddress(),
					vulkan.deviceAddress(),
					dataPath,
					dataPath,
				)
				assertNotNull(render, session.failure?.diagnostic())
				assertEquals(DlssSessionState.READY, session.state)

				block(native, session, adapter, render!!)
			}
		}
	}

	/**
	 * The family the fixture creates its queues in, discovered with a throwaway default fixture
	 * so the augmented fixture can be built with the extras keyed by the right family.
	 */
	private fun probeGraphicsQueueFamily(): Int =
		HeadlessVulkanFixture().use { it.graphicsQueueFamilyIndex() }

	private fun nativeLibrary(): Path {
		val library = Path.of("").toAbsolutePath().resolve("streamline/build/native/mc_dlss.dll")
		assertTrue(Files.isRegularFile(library), "buildNativeDlss must produce mc_dlss.dll")
		return library
	}

	private companion object {
		/** `VkFormat` values, which is the unit the flat ABI reports formats in. */
		const val VK_FORMAT_R16G16_SFLOAT = 83
		const val VK_FORMAT_R8G8B8A8_UNORM = 37
	}
}
