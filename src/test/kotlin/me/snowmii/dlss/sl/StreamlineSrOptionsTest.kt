package me.snowmii.dlss.sl

import java.nio.file.Path
import me.snowmii.dlss.NativeBridge
import me.snowmii.streamline.ExtensionBootstrap
import me.snowmii.dlss.bridge.HeadlessVulkanFixture
import me.snowmii.streamline.ImageBinding
import me.snowmii.streamline.Native
import me.snowmii.streamline.NativeApi
import me.snowmii.streamline.SrTagRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.lwjgl.vulkan.VK10

/**
 * M-3 rung: DLSS SR options and resource tagging through the native Streamline bridge.
 *
 * The optimal render dimensions answer from slDLSSGetOptimalSettings, configure records the
 * ABI's NGX-valued mode/preset on sl::DLSSOptions through slDLSSSetOptions, and the frame's
 * SR resources tag on the caller's live command buffer via slGetNewFrameToken +
 * slSetTagForFrame.
 *
 * The tag call deliberately does not require the module's motion/output images: there is no
 * NGX initialize in the SL path to acquire them before the first frame, so the engine's colour
 * and depth tag from the start and the module images join the tags once acquired for the
 * configured size. The live test therefore tags engine images only, and the buffer must still
 * submit clean.
 *
 * The whole device-backed scenario runs in ONE test method (and therefore one test fork),
 * and the fixture OUTLIVES the bridge: after the assertions the test records the
 * already-activated tuple through mc_dlss_initialize, so the bridge's close runs the orderly
 * slShutdown while the device is still alive. That close-path shutdown is what makes the
 * fork's exit clean (Streamline's runtime crashes its teardown when a process that called
 * DLSS plugin functions exits, sl.common.dll, the same known exit-crash family as
 * nvcuda64.dll), and a fork that followed such a crash comes up with the plugin manager
 * already initialized, which makes slSetVulkanInfo answer eErrorInvalidIntegration.
 * Splitting the scenario across two forks makes the second fork's activation fail on this
 * workstation no matter what it does.
 */
@NativeBridge
class StreamlineSrOptionsTest {

	@Test
	fun `SL options configure and SR resources tag on a live command buffer`(
		@TempDir dataPath: Path,
	) {
		withLiveSession(dataPath) { bridge, fixture ->
			val outputWidth = 2560
			val outputHeight = 1440
			// MaxQuality = 2 (NVSDK_NGX_PerfQuality_Value), which the bridge maps onto
			// sl::DLSSMode::eMaxQuality; preset K = 11 lands directly on the qualityPreset
			// field of sl::DLSSOptions for that mode.
			val dimensions = bridge.queryOptimalDimensions(outputWidth, outputHeight, 2)
			assertTrue(
				dimensions.width in 1..outputWidth,
				"queried render width must be in (0, output], got ${dimensions.width}",
			)
			assertTrue(
				dimensions.height in 1..outputHeight,
				"queried render height must be in (0, output], got ${dimensions.height}",
			)
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				bridge.configure(
					outputWidth,
					outputHeight,
					dimensions.width,
					dimensions.height,
					2,
					11,
				),
				"configure must record the SL options for the stored configuration",
			)

			// The engine's render-sized colour and depth, standing in for Minecraft's main
			// target and depth texture; the fixture leaves them in VK_IMAGE_LAYOUT_GENERAL,
			// which is the layout the tags name and the one validation checks them against.
			val color = fixture.createEngineImage(
				dimensions.width,
				dimensions.height,
				VK10.VK_FORMAT_R8G8B8A8_UNORM,
				VK10.VK_IMAGE_USAGE_SAMPLED_BIT or VK10.VK_IMAGE_USAGE_STORAGE_BIT,
				VK10.VK_IMAGE_ASPECT_COLOR_BIT,
			)
			val depth = fixture.createEngineImage(
				dimensions.width,
				dimensions.height,
				VK10.VK_FORMAT_D32_SFLOAT,
				VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,
				VK10.VK_IMAGE_ASPECT_DEPTH_BIT,
			)
			val commandBuffer = fixture.allocateAndBeginCommandBuffer()

			assertEquals(
				NativeApi.SUCCESS_RESULT,
				bridge.tagSrResources(
					SrTagRequest(
						commandBuffer.address(),
						ImageBinding(
							color.view(),
							color.image(),
							VK10.VK_FORMAT_R8G8B8A8_UNORM,
						),
						ImageBinding(
							depth.view(),
							depth.image(),
							VK10.VK_FORMAT_D32_SFLOAT,
						),
					),
				),
				"the engine's colour and depth must tag on the caller's command buffer",
			)

			// Tagging records nothing of its own to submit, so the recording must still be a
			// complete, valid command buffer the device accepts.
			fixture.endSubmitAndWait(commandBuffer)
			if (fixture.validationEnabled()) {
				val errors = fixture.validationErrorsAbout(color.image(), depth.image())
				assertTrue(
					errors.isEmpty(),
					"tagging must not leave the engine's images in a state validation rejects: $errors",
				)
			}
		}
	}

	/**
	 * Bootstraps Streamline and activates the Vulkan proxies against a headless device holding
	 * the merged queue layout, then executes [block] with the live bridge and fixture, and
	 * finally records the activated tuple through mc_dlss_initialize so the bridge's close
	 * shuts the Streamline runtime down.
	 *
	 * The fixture OUTLIVES the bridge: Native.close runs mc_dlss_close, which shuts Streamline
	 * down, and that must happen while the Vulkan device is still alive. The queue
	 * requirements come from a throwaway bridge closed before the device exists, when its
	 * close path is a no-op.
	 */
	private fun withLiveSession(dataPath: Path, block: (Native, HeadlessVulkanFixture) -> Unit) {
		val instanceExtensions = ExtensionBootstrap.queryInstanceExtensions()

		// The production merge starts from Minecraft's {graphicsFamily: 1} queue map and
		// adds SL's extra graphics and compute queues; the first graphics family is
		// compute-capable on this workstation, so both merges land in the same family.
		val graphicsFamily = probeGraphicsQueueFamily()
		HeadlessVulkanFixture(
			instanceExtensions,
			{ instance, physicalDevice ->
				val extensions = mutableListOf<String>()
				ExtensionBootstrap.addDeviceExtensions(extensions, instance, physicalDevice)
				extensions
			},
			true,
			mapOf(graphicsFamily to requirementsExtras()),
		).use { fixture ->
			Native.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
				)
				// The fixture creates one host queue in the family, so Streamline's own queues
				// start at index 1 - right after the host's, as slSetVulkanInfo records them.
				val hostQueueCount = 1
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.activateVulkanProxies(
						fixture.instanceAddress(),
						fixture.physicalDeviceAddress(),
						fixture.deviceAddress(),
						graphicsFamily,
						hostQueueCount,
						graphicsFamily,
						hostQueueCount,
					),
					"activation must succeed against the merged queue layout",
				)
				block(bridge, fixture)
				// Arm the close path: the already-activated tuple is recorded through the
				// existing initialize, so this bridge's close runs the orderly slShutdown while
				// the device is still alive instead of leaving the fork to crash at exit.
				SrLiveSession.recordActivatedSession(bridge, fixture, dataPath)
			}
		}
	}

	/** The summed extra graphics + compute queues the loaded SL features require. */
	private fun requirementsExtras(): Int {
		val requirements = Native.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				bridge.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
			)
			bridge.queryQueueRequirements()
		}
		return requirements.graphicsQueues + requirements.computeQueues
	}

	/**
	 * The family the fixture creates its queues in, discovered with a throwaway default fixture
	 * so the augmented fixture can be built with the extras keyed by the right family.
	 */
	private fun probeGraphicsQueueFamily(): Int =
		HeadlessVulkanFixture().use { it.graphicsQueueFamilyIndex() }
}
