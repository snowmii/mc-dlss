package me.snowmii.dlss.sl

import java.nio.file.Files
import java.nio.file.Path
import me.snowmii.dlss.bridge.ExtensionBootstrap
import me.snowmii.dlss.bridge.HeadlessVulkanFixture
import me.snowmii.dlss.bridge.ImageBinding
import me.snowmii.dlss.bridge.Native
import me.snowmii.dlss.bridge.NativeApi
import me.snowmii.dlss.bridge.SrTagRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
 * The whole device-backed scenario runs in ONE test method (and therefore one test fork):
 * Streamline's runtime crashes its teardown when a process that called DLSS plugin functions
 * exits (sl.common.dll, the same known exit-crash family as nvcuda64.dll), and a fork that
 * followed such a crash comes up with the plugin manager already initialized, which makes
 * slSetVulkanInfo answer eErrorInvalidIntegration. Splitting the scenario across two forks
 * makes the second fork's activation fail on this workstation no matter what it does.
 */
class StreamlineSrOptionsTest {

	@Test
	fun `SL options configure and SR resources tag on a live command buffer`() {
		withLiveSession { bridge, fixture ->
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
						commandBuffer = commandBuffer.address(),
						color = ImageBinding(
							color.view(),
							color.image(),
							VK10.VK_FORMAT_R8G8B8A8_UNORM,
						),
						depth = ImageBinding(
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

	@Test
	fun `SR options and tagging run through the Streamline seam`() {
		val apiSource = Files.readString(Path.of("native", "mc_dlss_api.cpp"))
		assertTrue(apiSource.contains("mc_dlss_tag_sr_resources"))

		val slSource = Files.readString(Path.of("native", "internal", "sl_dlss.cpp"))
		assertTrue(slSource.contains("slDLSSGetOptimalSettings"))
		assertTrue(slSource.contains("slDLSSSetOptions"))
		assertTrue(slSource.contains("slSetTagForFrame"))
		assertTrue(slSource.contains("slGetNewFrameToken"))

		val nativeApiSource = Files.readString(
			Path.of("src", "main", "java", "me", "snowmii", "dlss", "bridge", "NativeApi.java")
		)
		assertTrue(nativeApiSource.contains("tagSrResources"))

		val stateHeader = Files.readString(Path.of("native", "internal", "state.h"))
		assertTrue(stateHeader.contains("streamlineInitialized"))
	}

	/**
	 * Bootstraps Streamline and activates the Vulkan proxies against a headless device holding
	 * the merged queue layout, then runs [block] with the live bridge and fixture.
	 */
	private fun withLiveSession(block: (Native, HeadlessVulkanFixture) -> Unit) {
		val instanceExtensions = ExtensionBootstrap.queryInstanceExtensions()
		Native.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				bridge.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
			)
			val requirements = bridge.queryQueueRequirements()

			// The production merge starts from Minecraft's {graphicsFamily: 1} queue map and
			// adds SL's extra graphics and compute queues; the first graphics family is
			// compute-capable on this workstation, so both merges land in the same family.
			val graphicsFamily = probeGraphicsQueueFamily()
			val extras = requirements.graphicsQueues + requirements.computeQueues
			HeadlessVulkanFixture(
				instanceExtensions,
				{ instance, physicalDevice ->
					val extensions = mutableListOf<String>()
					ExtensionBootstrap.addDeviceExtensions(extensions, instance, physicalDevice)
					extensions
				},
				true,
				mapOf(graphicsFamily to extras),
			).use { fixture ->
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
			}
		}
	}

	/**
	 * The family the fixture creates its queues in, discovered with a throwaway default fixture
	 * so the augmented fixture can be built with the extras keyed by the right family.
	 */
	private fun probeGraphicsQueueFamily(): Int =
		HeadlessVulkanFixture().use { it.graphicsQueueFamilyIndex() }
}
