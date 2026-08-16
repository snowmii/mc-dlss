package me.snowmii.dlss.fg

import java.nio.file.Path
import me.snowmii.dlss.NativeBridge
import me.snowmii.dlss.bridge.EvaluationRequest
import me.snowmii.dlss.bridge.ExtensionBootstrap
import me.snowmii.dlss.bridge.FgTagRequest
import me.snowmii.dlss.bridge.HeadlessVulkanFixture
import me.snowmii.dlss.bridge.ImageBinding
import me.snowmii.dlss.bridge.Native
import me.snowmii.dlss.bridge.NativeApi
import me.snowmii.dlss.bridge.SrTagRequest
import me.snowmii.dlss.bridge.Vec2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.lwjgl.vulkan.VK10

/**
 * M-11 present-driven composition rung: ONE live SR+FG tagged frame runs the whole
 * present-driven chain - SR tag, FG tag, SR evaluation, present handoff - on ONE command
 * buffer under ONE shared Streamline frame token, submits validation-clean, and is
 * present-eligible exactly once.
 *
 * The M-9 rung composed the two tag sets and the M-10 rung proved the handoff state on a
 * frame that carried NO evaluation. This rung composes the frame the present path actually
 * consumes, and in doing so pins the one order the shared tag container makes legal: the
 * common plugin stores ONE tag per (buffer type, viewport) per frame, so the SR and FG tags
 * for the shared depth and motion inputs land in the same slots, and the LAST writer owns
 * the slot the next reader sees. The composed frame therefore records the FG tag first (it
 * obtains the retained frame token), the SR tag second under that same token (the equal-index
 * oracle), and the SR evaluation third - the evaluation reads the depth and motion slots as
 * they stand when it records, so the SR tag must be the last writer before it, or the
 * evaluation would read the FG tags' GENERAL declarations against images the evaluation
 * already moved to SHADER_READ_ONLY, which validation rejects. The evaluation's restore then
 * leaves every FG-tagged resource in the engine-resting GENERAL layout, and the frame's FG
 * tag re-records after it - the evaluation retained the shared token, so the re-declaration
 * lands on the same frame index and hands the present path the FG GENERAL declarations the
 * images actually rest in until Present, the M-10 'depth/motion remain GENERAL and valid
 * until Present' discipline now with the evaluation that moves them out of GENERAL present
 * on the frame. The present handoff then re-records the stored DLSS-G options for the
 * complete equal-index set and consumes the frame token - exactly once. The single buffer
 * submits under the Khronos validation layer, which is the only oracle for the layout
 * composition: the SR evaluation's transitions move the shared depth and motion inputs to
 * SHADER_READ_ONLY and back, and the frame must end with every FG-tagged resource back in
 * the engine-resting GENERAL layout the FG tag declared for its valid-until-present lifetime.
 *
 * The present path itself - Streamline's vkQueuePresentKHR interception and the DLSS-G
 * generation it records - is M-11 production wiring, not this rung: the headless fixture never
 * presents. What the rung proves is the recorded frame the present path consumes: complete
 * equal-index tags, a validation-clean evaluation on the shared token and buffer, and a
 * handoff that answers success once and refuses the consumed set afterwards.
 *
 * The whole scenario runs in ONE test method (and therefore one test fork) like every other
 * live rung: the close-path slShutdown is what makes the fork's exit clean, and a fork that
 * followed an unclean exit comes up with the plugin manager already initialized.
 */
@NativeBridge
class PresentIntegrationTest {

	@Test
	fun `one live SR plus FG frame composes evaluation and handoff on one token and buffer and is present-eligible exactly once`(
		@TempDir dataPath: Path,
	) {
		// Pre-init: this fork's module has never bootstrapped, so the handoff seam has no
		// Streamline session to answer through. The check runs before the live session below
		// and on a throwaway bridge, and the module's bootstrap state is what it asserts
		// against.
		Native.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
			assertEquals(
				FAIL_NOT_INITIALIZED,
				bridge.presentHandoff(),
				"presentHandoff before bootstrap must answer FAIL_NotInitialized",
			)
		}

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
				// The stored DLSS-G options: the handoff re-records these at present time with
				// the declared back-buffer count, and the FG tags name the same extents and
				// formats this record declared.
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
				// frame's set, the evaluation reads and writes, and the FG tag reads the
				// motion source from - only once acquired at the configured size.
				val images = bridge.acquireImages()
				assertTrue(images != null, "module images must be acquired before the frame")

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

				// The validation oracle's baseline: the pinned Streamline runtime's own device
				// creation (its DLSS-G optical-flow device) records device-extension complaints
				// that predate any frame. The clean-frame assertion subtracts this snapshot by
				// COUNT, not by message equality - the fixture appends every error-severity
				// message in arrival order and never clears, so the snapshot is a strict prefix
				// of the final list and every arrival after it is attributed to the frame.
				// Subtracting by message would hide a frame error that duplicates a baseline
				// message's text; subtracting by count cannot.
				val validationBaseline = fixture.validationErrors()

				// One frame, one buffer, one shared frame token. The FG tag records FIRST and
				// obtains the retained token; the SR tag records second under that same token
				// instead of advancing the frame - the common plugin's container holds one tag
				// per (buffer type, viewport) per frame, and the SR evaluation reads the depth
				// and motion slots as they stand when it records, so the SR tag must be the last
				// writer before it, or the evaluation would read the FG tags' GENERAL
				// declarations against images the evaluation already moved to SHADER_READ_ONLY,
				// which validation rejects. The SR evaluation records third against the retained
				// token; the composed frame keeps the token, and the FG tag re-records fourth
				// under it - the evaluation's restore leaves every FG-tagged resource in the
				// engine-resting GENERAL layout, and the re-declaration hands the present path
				// the FG GENERAL declarations those resources actually rest in. The present
				// handoff then re-records the stored DLSS-G options for the complete equal-index
				// tag set and consumes the frame token - exactly once. The evaluation is what
				// the M-10 handoff rung deliberately left off the frame: it is the stage whose
				// transitions move the shared depth and motion inputs out of GENERAL and back,
				// and the composed frame must end with every FG-tagged resource in the declared
				// engine-resting layout for the present path to consume.
				val frame = fixture.allocateAndBeginCommandBuffer()
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
					"the FG tag must record first on the caller's command buffer",
				)
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
					"the SR tag must record last so the evaluation reads its SHADER_READ_ONLY declarations",
				)
				// The composed frame's identity oracle: the SR and FG tags must have landed
				// under ONE Streamline frame index - the SR tag reused the FG tag's retained
				// token rather than advancing the frame, and a fresh slGetNewFrameToken would
				// have produced a strictly later index under the SR slot.
				val indexes = bridge.taggedFrameIndexes()
				assertEquals(
					indexes.srFrameIndex,
					indexes.fgFrameIndex,
					"the SR and FG tags of the composed frame must record under the same " +
						"Streamline frame index, got SR=${indexes.srFrameIndex}, FG=${indexes.fgFrameIndex}",
				)
				// The SR evaluation records on the same buffer against the retained token, the
				// way FrameEvaluation records it in production: the engine's colour and depth
				// and the module's motion image transition to the read state, the output to the
				// storage state, and the engine's images go back to GENERAL in the same
				// recording. The motion image must end the evaluation in the engine-resting
				// layout too: the FG tag declared it GENERAL for its valid-until-present
				// lifetime, and the composed frame leaves every FG-tagged resource in the
				// layout its tag declared.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.evaluate(
						EvaluationRequest(
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
							// The offset is in render pixels, the unit the jitter sequence is in.
							// The motion buffer is normalized device units, so the scale that
							// normalizes it onto [-1,1] is one.
							jitter = Vec2(0.25f, -0.5f),
							motionScale = Vec2(1f, 1f),
							frameTimeMilliseconds = 16.6f,
							resetHistory = true,
							renderDimensions = dimensions,
						),
					),
					"the SR evaluation must record on the tagged frame's shared buffer",
				)
				// The composed frame re-declares its FG tags after the evaluation, under the
				// SAME retained token (the evaluation keeps it on a composed frame so the
				// re-declaration cannot advance the frame): the evaluation's restore left every
				// FG-tagged resource in the engine-resting layout, and the common plugin holds
				// ONE declaration per (buffer type, viewport) per frame - the SR tag's
				// SHADER_READ_ONLY records for the shared depth and motion slots served the
				// evaluation, but the present path reads those slots as they stand now, so they
				// must declare the FG GENERAL layouts the images actually rest in until Present.
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
					"the FG tag must re-record after the evaluation so the present path reads its " +
						"engine-resting declarations",
				)
				// Present-eligible exactly once: the complete equal-index tag set of the
				// composed frame hands off, re-recording the stored 2x options with the
				// back-buffer count mc_dlss_configure_fg declared, and consuming the frame token
				// the evaluation retained. The handoff records no GPU work - the frame's tagged
				// resources stay in the layouts the tags declared until Streamline's present path
				// consumes them.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.presentHandoff(),
					"the composed frame's complete equal-index tag set must hand off once",
				)
				assertEquals(
					FAIL_INVALID_PARAMETER,
					bridge.presentHandoff(),
					"a second handoff for the composed frame's consumed tag set must answer " +
						"FAIL_InvalidParameter: the frame is present-eligible exactly once",
				)
				fixture.endSubmitAndWait(frame)
				assertComposedFrameClean(fixture, validationBaseline)
			}
		}
	}

	/**
	 * Asserts the rung's validation oracle was actually running, then that the Khronos
	 * validation layer reported NO new error-severity message since the frame's recording
	 * began - every error the layer saw in the frame-attributable window, not only errors
	 * naming one of the frame's six tagged images. The frame's six images are the only
	 * resources this test ever submits, so any error the layer reports inside that window is
	 * attributable to the composed frame: a message about a different handle is still a
	 * frame-attributable failure (the layer names what it blames, but the fix for it is on
	 * this frame's recording), and filtering by handle would let a real layout defect slip
	 * through whenever the message names a handle this list does not anticipate.
	 *
	 * Errors the layer reported before the window are not frame-attributable and do not fail
	 * the rung: the pinned Streamline runtime's own device creation records device-extension
	 * complaints (its DLSS-G optical-flow device) that no repair inside this slice's scope
	 * could clear, and they would otherwise make the clean-frame assertion worthless noise.
	 *
	 * The baseline is subtracted by COUNT, not by message equality: the fixture appends every
	 * error-severity message in arrival order and never clears, so the baseline snapshot is a
	 * strict prefix of the final error list, and the frame-attributable window is exactly the
	 * arrival-order suffix after it. Asserting the prefix first pins that append-only
	 * invariant - if the snapshot were not a prefix, the subtraction would misattribute.
	 * Subtracting by message equality instead would hide a frame error whose text duplicates
	 * a baseline message's, and a repeated error is exactly what a layout defect slipping
	 * through once is likely to do again on the next submission.
	 *
	 * Validation is the only oracle for image layouts: a transition whose oldLayout does not
	 * match the image's actual layout is undefined behaviour to the driver and silent
	 * without it, so a session whose layer could not be enabled has no evidence worth
	 * asserting on and the rung FAILS rather than silently skipping the clean check.
	 */
	private fun assertComposedFrameClean(
		fixture: HeadlessVulkanFixture,
		validationBaseline: List<String>,
	) {
		assertTrue(
			fixture.validationEnabled(),
			"the rung needs the Khronos validation layer (VK_LAYER_KHRONOS_validation " +
				"plus VK_EXT_debug_utils); without it the clean-frame assertion is worthless",
		)
		val errors = fixture.validationErrors()
		assertEquals(
			validationBaseline,
			errors.take(validationBaseline.size),
			"the baseline snapshot must be a strict prefix of the final error list - the " +
				"fixture appends in arrival order and never clears, so a mismatch means the " +
				"count-based subtraction cannot attribute the suffix",
		)
		val frameErrors = errors.drop(validationBaseline.size)
		assertTrue(
			frameErrors.isEmpty(),
			"the composed frame must submit with no validation error at all: $frameErrors",
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

	private companion object {
		/** NVSDK_NGX_Result_FAIL_NotInitialized = NVSDK_NGX_Result_Fail | 7 (0xBAD00000 | 7). */
		const val FAIL_NOT_INITIALIZED = 0xBAD00007.toInt()

		/** NVSDK_NGX_Result_FAIL_InvalidParameter = NVSDK_NGX_Result_Fail | 5 (0xBAD00000 | 5). */
		const val FAIL_INVALID_PARAMETER = 0xBAD00005.toInt()
	}
}
