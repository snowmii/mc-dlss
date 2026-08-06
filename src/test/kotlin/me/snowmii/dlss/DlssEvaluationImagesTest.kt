package me.snowmii.dlss

import java.nio.file.Files
import java.nio.file.Path
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
 * headless fixture the M-6 rung uses: an image handle allocated against a real driver is either
 * genuinely there or the call failed.
 *
 * Teardown order matters: native release must complete before Vulkan destruction, so the native
 * handle closes (inner) before the fixture closes (outer).
 */
class DlssEvaluationImagesTest {
	private val output = DlssDimensions(2560, 1440)

	@Test
	fun `configured dimensions produce reusable native motion and output images`(@TempDir dataPath: Path) {
		withReadySession(dataPath) { _, session, adapter, render ->
			val images = adapter.acquireImages()

			assertNotNull(images, session.failure?.diagnostic())
			assertTrue(images!!.motionImage != 0L, "motion image must be a real VkImage")
			assertTrue(images.motionView != 0L, "motion image must carry a view")
			assertTrue(images.outputImage != 0L, "output image must be a real VkImage")
			assertTrue(images.outputView != 0L, "output image must carry a view")
			assertNotEquals(images.motionImage, images.outputImage)
			assertNotEquals(images.motionView, images.outputView)
			// R16G16_SFLOAT for two-channel screen-space motion, R8G8B8A8_UNORM to match the
			// main target the output is copied into. Both are mandatory storage formats.
			assertEquals(VK_FORMAT_R16G16_SFLOAT, images.motionFormat)
			assertEquals(VK_FORMAT_R8G8B8A8_UNORM, images.outputFormat)

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
			assertTrue(rebuilt!!.motionView != 0L)
			assertTrue(adapter.releaseImages())
		}
	}

	@Test
	fun `a configuration change replaces the images rather than reusing the old size`(
		@TempDir dataPath: Path,
	) {
		withReadySession(dataPath) { native, session, adapter, render ->
			val first = adapter.acquireImages()
			assertNotNull(first, session.failure?.diagnostic())

			// Half the render width is still a legal DLSS configuration for this output size,
			// and it is a different allocation from the one the quality mode produced.
			val reconfigured = native.configure(
				output.width,
				output.height,
				render.width / 2,
				render.height / 2,
				DlssQualityMode.PERFORMANCE.ngxValue,
			)
			assertEquals(DlssNativeApi.SUCCESS_RESULT, reconfigured)

			val second = adapter.acquireImages()
			assertNotNull(second, session.failure?.diagnostic())
			assertNotEquals(first!!.motionView, second!!.motionView)
			assertTrue(adapter.releaseImages())
		}
	}

	@Test
	fun `acquiring before the bridge is initialized allocates nothing`() {
		val library = nativeLibrary()
		DlssNative.open(library).use { native ->
			val failure = runCatching { native.acquireImages() }.exceptionOrNull()

			assertTrue(
				failure is DlssNativeException,
				"an uninitialized bridge must refuse rather than allocate: $failure",
			)
			assertEquals("acquire-images", (failure as DlssNativeException).stage())
			// Releasing what was never acquired is still success, so teardown never has to ask.
			assertEquals(DlssNativeApi.SUCCESS_RESULT, native.releaseImages())
		}
	}

	/**
	 * Drives the production path up to READY on the real device, then hands the caller the
	 * bridge, session, adapter, and NGX-queried render dimensions.
	 */
	private fun withReadySession(
		dataPath: Path,
		block: (DlssNative, DlssSession, DlssLifecycleAdapter, DlssDimensions) -> Unit,
	) {
		val library = nativeLibrary()
		val ngxRuntime = ngxRuntimeDirectory()
		val instanceExtensions = DlssNative.open(library).use { it.queryInstanceExtensions() }

		HeadlessVulkanFixture(instanceExtensions) { vkInstance, vkPhysicalDevice ->
			DlssNative.open(library).use { it.queryDeviceExtensions(vkInstance, vkPhysicalDevice) }
		}.use { vulkan ->
			DlssNative.open(library).use { native ->
				val session = DlssSession(
					DlssStartupConfig(
						enabled = true,
						qualityMode = DlssQualityMode.QUALITY,
						outputDimensions = output,
						sdkPath = ngxRuntime,
						nativeLibraryPath = library,
						dataPath = dataPath,
						warnings = emptyList(),
					),
				)
				val adapter = DlssLifecycleAdapter(session, native)
				val render = adapter.initialize(
					vulkan.instanceAddress(),
					vulkan.physicalDeviceAddress(),
					vulkan.deviceAddress(),
					ngxRuntime,
					dataPath,
				)
				assertNotNull(render, session.failure?.diagnostic())
				assertEquals(DlssSessionState.READY, session.state)

				block(native, session, adapter, render!!)
			}
		}
	}

	private fun nativeLibrary(): Path {
		val library = Path.of("").toAbsolutePath().resolve("build/native/mc_dlss.dll")
		assertTrue(Files.isRegularFile(library), "buildNativeDlss must produce mc_dlss.dll")
		return library
	}

	private fun ngxRuntimeDirectory(): Path {
		val runtime = Path.of(
			"C:/Users/miuki/Development/NVIDIA/mc-dlss/dlss-sdk-v310.7.0/DLSS-310.7.0/lib/Windows_x86_64/rel",
		)
		assertTrue(Files.isDirectory(runtime), "Pinned NGX runtime directory must exist")
		return runtime
	}

	private companion object {
		/** `VkFormat` values, which is the unit the flat ABI reports formats in. */
		const val VK_FORMAT_R16G16_SFLOAT = 83
		const val VK_FORMAT_R8G8B8A8_UNORM = 37
	}
}
