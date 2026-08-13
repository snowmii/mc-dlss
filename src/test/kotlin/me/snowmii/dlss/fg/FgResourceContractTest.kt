package me.snowmii.dlss.fg

import java.nio.file.Path
import me.snowmii.dlss.bridge.ExtensionBootstrap
import me.snowmii.dlss.bridge.FgTagRequest
import me.snowmii.dlss.bridge.HeadlessVulkanFixture
import me.snowmii.dlss.bridge.ImageBinding
import me.snowmii.dlss.bridge.Native
import me.snowmii.dlss.bridge.NativeApi
import me.snowmii.dlss.bridge.SrTagRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.lwjgl.vulkan.VK10

/**
 * M-9 composed rung: one live session tags the SR frame's resources and the DLSS-G frame's
 * resources on the SAME command buffer under ONE shared frame token, and the frame submits
 * validation-clean.
 *
 * The two tag rungs proved each feature's tags alone against a live session; this rung
 * composes them, which is the milestone's M-9 / AC-2 contract: a frame that reaches the
 * present-time DLSS-G evaluation carries the SR tags (scaling input colour, depth, motion
 * vectors, scaling output colour) and the DLSS-G tags (depth, motion vectors, HUD-less
 * colour, UI colour+alpha) recorded against the same frame index. The SR tag records first
 * and obtains the frame token (slGetNewFrameToken), retaining it in module state; the FG tag
 * records second and reuses that retained token rather than advancing the frame - the
 * retained token is what the frame's evaluation consumes, so every tag of the frame must land
 * under one frame index. The identity is not observable through the tag calls themselves (the
 * token never crosses the ABI), so the rung queries the frame index each call tagged under
 * (mc_dlss_query_tagged_frame_indexes) and asserts the two are equal. Both calls must
 * succeed on the caller's shared recording, and the single buffer must submit clean under
 * the Khronos validation layer: the two tag sets share the engine's render-sized depth image
 * and the module's motion image, and a tag that recorded a transition the images' actual
 * layouts cannot serve would fail exactly there.
 *
 * The whole scenario runs in ONE test method (and therefore one test fork) like every other
 * live rung: the close-path slShutdown is what makes the fork's exit clean, and a fork that
 * followed an unclean exit comes up with the plugin manager already initialized.
 */
class FgResourceContractTest {

	@Test
	fun `one live session tags SR and FG resources on one buffer under one shared frame token and submits clean`(
		@TempDir dataPath: Path,
	) {
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

				// The stored SR configuration: the queried render dimensions and the quality
				// mode/preset the SR options record, exactly as the SR rungs drive them.
				val outputWidth = 2560
				val outputHeight = 1440
				// MaxQuality = 2 (NVSDK_NGX_PerfQuality_Value), which the bridge maps onto
				// sl::DLSSMode::eMaxQuality; preset K = 11 lands on the qualityPreset field.
				val dimensions = bridge.queryOptimalDimensions(outputWidth, outputHeight, 2)
				assertTrue(
					dimensions.width in 1..outputWidth &&
						dimensions.height in 1..outputHeight,
					"queried render dimensions must be in (0, output], got $dimensions",
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
					"configure must record the SR options for the stored configuration",
				)
				// The stored DLSS-G options: the frame's FG tags name the same extents and
				// formats this record declared, so both tag sets record against one stored
				// configuration.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.configureFg(3),
					"after a ready session and a stored configuration the FG options must record",
				)
				// initialize records the activated tuple (arming the bridge's close path to
				// shut Streamline down while the device is alive) and makes the session ready
				// for image acquisition.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.initialize(
						fixture.instanceAddress(),
						fixture.physicalDeviceAddress(),
						fixture.deviceAddress(),
						dataPath,
						dataPath,
					),
					"initialize must record the activated Vulkan tuple",
				)
				// The module's own motion and output images, which the SR tag adds to the
				// frame's set and the FG tag reads the motion source from, only once acquired
				// at the configured size.
				val images = bridge.acquireImages()
				assertTrue(images != null, "module images must be acquired before the two tag sets")

				// The engine's render-sized colour and depth and its output-sized HUD-less and
				// UI targets, standing in for Minecraft's main target, depth texture, and the
				// split's two targets; the fixture leaves them in VK_IMAGE_LAYOUT_GENERAL,
				// which is where Minecraft rests its own textures.
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
					VK10.VK_IMAGE_USAGE_SAMPLED_BIT or VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,
					VK10.VK_IMAGE_ASPECT_DEPTH_BIT,
				)
				val hudless = fixture.createEngineImage(
					outputWidth,
					outputHeight,
					VK10.VK_FORMAT_R8G8B8A8_UNORM,
					VK10.VK_IMAGE_USAGE_SAMPLED_BIT or VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
					VK10.VK_IMAGE_ASPECT_COLOR_BIT,
				)
				val ui = fixture.createEngineImage(
					outputWidth,
					outputHeight,
					VK10.VK_FORMAT_R8G8B8A8_UNORM,
					VK10.VK_IMAGE_USAGE_SAMPLED_BIT or VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
					VK10.VK_IMAGE_ASPECT_COLOR_BIT,
				)

				// One frame, one buffer, one shared frame token: the SR tag records first and
				// obtains the retained token, and the FG tag records second under that same
				// token instead of advancing the frame. Both tag sets land on the caller's
				// shared recording - the engine's depth image and the module's motion image
				// are tagged by both, so the frame's two features read one set of inputs -
				// and the frame must submit clean.
				val frame = fixture.allocateAndBeginCommandBuffer()
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagSrResources(
						SrTagRequest(
							commandBuffer = frame.address(),
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
					"the SR tag must record on the caller's command buffer",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagFgResources(
						FgTagRequest(
							commandBuffer = frame.address(),
							depth = ImageBinding(
								depth.view(),
								depth.image(),
								VK10.VK_FORMAT_D32_SFLOAT,
							),
							hudless = ImageBinding(
								hudless.view(),
								hudless.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							),
							ui = ImageBinding(
								ui.view(),
								ui.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							),
						),
					),
					"the FG tag must record on the same buffer under the SR tag's retained frame token",
				)
				// The composed rung's identity oracle: the frame index Streamline numbered the SR
				// tag under and the one it numbered the FG tag under must be the same. The FG tag
				// reused the SR tag's retained token rather than advancing the frame, so a fresh
				// slGetNewFrameToken would have produced a strictly later index under the FG
				// slot; equality is the behavior-level proof both slSetTagForFrame calls landed
				// on one frame index.
				val indexes = bridge.taggedFrameIndexes()
				assertEquals(
					indexes.srFrameIndex,
					indexes.fgFrameIndex,
					"the SR and FG tags of one frame must record under the same Streamline frame " +
						"index, got SR=${indexes.srFrameIndex}, FG=${indexes.fgFrameIndex}",
				)
				fixture.endSubmitAndWait(frame)
				assertComposedTagFrameClean(fixture, color, depth, hudless, ui, images!!)
			}
		}
	}

	/**
	 * Asserts the rung's validation oracle was actually running, then that the Khronos
	 * validation layer reported no errors naming any of the frame's six tagged images - the
	 * engine's colour, depth, HUD-less, and UI targets, and the module's motion and output
	 * images.
	 *
	 * Validation is the only oracle for image layouts: a tag that recorded a transition the
	 * images' actual layouts cannot serve is undefined behaviour to the driver and silent
	 * without it, so a session whose layer could not be enabled has no evidence worth
	 * asserting on and the rung FAILS rather than silently skipping the clean check.
	 */
	private fun assertComposedTagFrameClean(
		fixture: HeadlessVulkanFixture,
		color: HeadlessVulkanFixture.EngineImage,
		depth: HeadlessVulkanFixture.EngineImage,
		hudless: HeadlessVulkanFixture.EngineImage,
		ui: HeadlessVulkanFixture.EngineImage,
		images: me.snowmii.dlss.bridge.DlssEvaluationImages,
	) {
		assertTrue(
			fixture.validationEnabled(),
			"the rung needs the Khronos validation layer (VK_LAYER_KHRONOS_validation " +
				"plus VK_EXT_debug_utils); without it the clean-frame assertion is worthless",
		)
		val errors = fixture.validationErrorsAbout(
			color.image(),
			depth.image(),
			hudless.image(),
			ui.image(),
			images.motion.image,
			images.output.image,
		)
		assertTrue(
			errors.isEmpty(),
			"the composed tagged frame must not leave a resource in a state validation " +
				"rejects: $errors",
		)
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
