package me.snowmii.dlss.fg

import java.nio.file.Files
import java.nio.file.Path
import me.snowmii.dlss.NativeBridge
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.EvaluationImages
import me.snowmii.streamline.FrameTimings
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.dlss.bridge.ExtensionBootstrap
import me.snowmii.streamline.FgTagRequest
import me.snowmii.dlss.bridge.HeadlessVulkanFixture
import me.snowmii.streamline.ImageBinding
import me.snowmii.streamline.MotionRequest
import me.snowmii.dlss.bridge.Native
import me.snowmii.dlss.bridge.NativeApi
import me.snowmii.streamline.PresentTarget
import me.snowmii.streamline.SrTagRequest
import me.snowmii.dlss.session.DlssNativeStage
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssSessionState
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.session.SRMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.lwjgl.vulkan.VK10

/**
 * M-10 rung: the per-frame present-handoff eligibility records through the native/Kotlin ABI
 * seam, accepts exactly one complete current-frame SR+FG tag set under equal frame indexes,
 * and rejects missing options, partial tags, and consumed eligibility without side effects.
 *
 * Streamline 2.12.0 rejects slEvaluateFeature(kFeatureDLSS_G) with eErrorMissingOrInvalidAPI,
 * so M-10 cannot evaluate DLSS-G: generation belongs to Streamline's present interception at
 * M-11. What M-10 proves is the handoff state that present consumes - the per-frame
 * slDLSSGSetOptions re-record the guide requires, and a frame whose SR and FG tags both
 * recorded under one frame index. The handoff records no GPU work: depth and motion keep the
 * GENERAL layout and eValidUntilPresent lifetime the FG tag declared, and the frame submits
 * validation-clean.
 *
 * The rung deliberately does not run the SR evaluation on the tagged frame: the DLSS-G
 * plugin processes its tags (transitioning from the declared GENERAL state) when the SR
 * evaluation records on the shared frame token, and the SR evaluate's own transitions move
 * the shared depth and motion inputs to SHADER_READ_ONLY first - a validation conflict that
 * is the present-path composition M-11 owns, not the handoff state this rung proves. M-10's
 * boundary is the handoff frame: complete tags, no GPU work, validation-clean submission.
 *
 * The live scenario drives the seam itself, mirroring the M-9 rungs: a fresh fork's module
 * has no Streamline session, so the handoff answers FAIL_NotInitialized before bootstrap; a
 * ready session refuses before the FG options recorded, before the module's images were
 * acquired, before any tag, and with only the SR tag recorded; the complete equal-index set
 * hands off once; a second handoff for the same tag set is refused; re-recording only one
 * tag side stays refused until the counterpart also records (each tag marks only its own
 * side fresh, and the handoff consumes both); a configuration replacement, the public
 * release-images boundary, and reset each invalidate the tag records so the stale set stays
 * refused until both sides re-record under a fresh token; and a set armed under live images
 * that then release is refused again after the images re-acquire until both sides re-record.
 * The whole scenario runs in ONE test method (and therefore one test
 * fork): the close-path slShutdown is what makes the fork's exit clean, and a fork that
 * followed an unclean exit comes up with the plugin manager already initialized.
 */
@NativeBridge
class FgEvaluationTest {

	@Test
	fun `one live session hands off one complete equal-index frame and refuses partial or consumed eligibility`(
		@TempDir dataPath: Path,
	) {
		// Pre-init: this fork's module has never bootstrapped, so the handoff has no
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

				// Missing options: the handoff re-records what mc_dlss_configure_fg stored,
				// so a session whose DLSS-G options never recorded has nothing to hand off
				// with and must refuse before anything is re-recorded.
				assertEquals(
					FAIL_INVALID_PARAMETER,
					bridge.presentHandoff(),
					"a handoff before the FG options recorded must answer FAIL_InvalidParameter",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.configureFg(3),
					"after a ready session and a stored configuration the FG options must record",
				)

				// The FG tags reference the module's motion image; before mc_dlss_acquire_images
				// there is no motion source at the configured size for a present to read.
				assertEquals(
					FAIL_INVALID_PARAMETER,
					bridge.presentHandoff(),
					"a handoff before the module's images were acquired must answer " +
						"FAIL_InvalidParameter",
				)
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
				val images = bridge.acquireImages()
				assertTrue(images != null, "module images must be acquired before the handoff")

				// No tags yet: the present path reads the frame's SR and FG tags together, so
				// a frame neither tag set recorded for cannot hand off.
				assertEquals(
					FAIL_INVALID_PARAMETER,
					bridge.presentHandoff(),
					"a handoff before any tag recorded must answer FAIL_InvalidParameter",
				)

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
				// token. The rung proves the handoff frame itself - complete tags, no GPU work -
				// and leaves the SR evaluation off the frame: the DLSS-G plugin processes its
				// tags when the evaluation records, which is the present-path composition M-11
				// owns (see the class comment).
				val frame = fixture.allocateAndBeginCommandBuffer()
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagSrResources(
						SrTagRequest(
							frame.address(),
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
					"the SR tag must record on the caller's command buffer",
				)
				// Partial tags: the FG tag has not recorded, and the present path reads the
				// two tag sets together. The refusal must not clear the SR tag's record or
				// consume anything - the completed frame below has to hand off all the same.
				assertEquals(
					FAIL_INVALID_PARAMETER,
					bridge.presentHandoff(),
					"a handoff with only the SR tag recorded must answer FAIL_InvalidParameter",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagFgResources(
						FgTagRequest(
							frame.address(),
							ImageBinding(
								depth.view(),
								depth.image(),
								VK10.VK_FORMAT_D32_SFLOAT,
							),
							ImageBinding(
								hudless.view(),
								hudless.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							),
							ImageBinding(
								ui.view(),
								ui.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							),
						),
					),
					"the FG tag must record on the same buffer under the SR tag's retained frame token",
				)
				// The equal-index oracle: the SR and FG tags of the frame must have landed
				// under one Streamline frame index, which is what makes the tag set complete
				// enough to hand off.
				val indexes = bridge.taggedFrameIndexes()
				assertEquals(
					indexes.srFrameIndex,
					indexes.fgFrameIndex,
					"the SR and FG tags of one frame must record under the same Streamline " +
						"frame index, got SR=${indexes.srFrameIndex}, FG=${indexes.fgFrameIndex}",
				)
				// The complete equal-index tag set hands off: the stored 2x options re-record
				// with the back-buffer count mc_dlss_configure_fg declared, and the frame is
				// present-eligible.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.presentHandoff(),
					"the complete equal-index tag set must hand off once",
				)
				// Consumed: the frame's tag set already handed off, and Streamline's present
				// path must not see the same tag set handed off twice. The refusal re-records
				// nothing and clears nothing - the fully re-recorded set below hands off again.
				assertEquals(
					FAIL_INVALID_PARAMETER,
					bridge.presentHandoff(),
					"a second handoff for the same tag set must answer FAIL_InvalidParameter",
				)
				// Each tag record marks only its own side of the tag set fresh: re-recording just
				// the FG side after the consumed handoff must NOT revive the eligibility, because
				// the SR side is still stale - a partial re-tag can never re-arm a handoff on its
				// own. The refused handoff above left the tag state and the options exactly as
				// the frame ended, with nothing consumed but the eligibility itself.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagFgResources(
						FgTagRequest(
							frame.address(),
							ImageBinding(
								depth.view(),
								depth.image(),
								VK10.VK_FORMAT_D32_SFLOAT,
							),
							ImageBinding(
								hudless.view(),
								hudless.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							),
							ImageBinding(
								ui.view(),
								ui.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							),
						),
					),
					"re-recording the frame's FG tag must succeed after a consumed handoff",
				)
				assertEquals(
					FAIL_INVALID_PARAMETER,
					bridge.presentHandoff(),
					"re-recording only the FG tag must not re-arm a consumed handoff while the " +
						"SR tag is still stale",
				)
				// The counterpart records too, and the re-recorded complete set hands off once -
				// the same retained token and the same frame index, exactly the repeated-tag
				// behaviour the tag rung proves.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagSrResources(
						SrTagRequest(
							frame.address(),
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
					"re-recording the frame's SR tag must succeed after the FG side re-recorded",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.presentHandoff(),
					"the re-recorded complete tag set must hand off once",
				)
				// Symmetric per-side freshness: repeating only the SR side now stays refused
				// until the FG side also records.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagSrResources(
						SrTagRequest(
							frame.address(),
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
					"re-recording the frame's SR tag must succeed after a consumed handoff",
				)
				assertEquals(
					FAIL_INVALID_PARAMETER,
					bridge.presentHandoff(),
					"re-recording only the SR tag must not re-arm a consumed handoff while the " +
						"FG tag is still stale",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagFgResources(
						FgTagRequest(
							frame.address(),
							ImageBinding(
								depth.view(),
								depth.image(),
								VK10.VK_FORMAT_D32_SFLOAT,
							),
							ImageBinding(
								hudless.view(),
								hudless.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							),
							ImageBinding(
								ui.view(),
								ui.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							),
						),
					),
					"re-recording the frame's FG tag must succeed after the SR side re-recorded",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.presentHandoff(),
					"the fully re-recorded tag set must hand off once",
				)

				// The public release-images boundary: an armed complete set does not survive an
				// image release. The set re-arms and hands off once (proving it eligible under the
				// current configured and acquired images), re-arms a second fresh set, and then
				// the images release; a handoff is refused while the images are gone, stays
				// refused after they re-acquire because the records are stale, stays refused when
				// only the SR side re-records (the FG side is still stale), and only the complete
				// fresh set - both sides re-recorded under the fresh token - hands off once.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagSrResources(
						SrTagRequest(
							frame.address(),
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
					"the pre-release SR tag must record to re-arm the complete set",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagFgResources(
						FgTagRequest(
							frame.address(),
							ImageBinding(
								depth.view(),
								depth.image(),
								VK10.VK_FORMAT_D32_SFLOAT,
							),
							ImageBinding(
								hudless.view(),
								hudless.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							),
							ImageBinding(
								ui.view(),
								ui.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							),
						),
					),
					"the pre-release FG tag must record to complete the re-armed set",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.presentHandoff(),
					"the re-armed complete set must hand off once under the current images",
				)
				// The second fresh set is the one the release invalidates: it is complete and
				// eligible (the handoff above proved the identical shape), so the refusals below
				// are the release's doing, not a set that was never eligible.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagSrResources(
						SrTagRequest(
							frame.address(),
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
					"the second fresh SR tag must record for the release to invalidate",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagFgResources(
						FgTagRequest(
							frame.address(),
							ImageBinding(
								depth.view(),
								depth.image(),
								VK10.VK_FORMAT_D32_SFLOAT,
							),
							ImageBinding(
								hudless.view(),
								hudless.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							),
							ImageBinding(
								ui.view(),
								ui.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							),
						),
					),
					"the second fresh FG tag must record for the release to invalidate",
				)
				val preReleaseIndexes = bridge.taggedFrameIndexes()
				assertEquals(
					preReleaseIndexes.srFrameIndex,
					preReleaseIndexes.fgFrameIndex,
					"the pre-release tags must record under one equal frame index, got " +
						"SR=${preReleaseIndexes.srFrameIndex}, FG=${preReleaseIndexes.fgFrameIndex}",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.releaseImages(),
					"releaseImages must succeed on a ready session with acquired images",
				)
				assertEquals(
					FAIL_INVALID_PARAMETER,
					bridge.presentHandoff(),
					"a handoff while the images are released must answer FAIL_InvalidParameter",
				)
				val reacquiredAfterRelease = bridge.acquireImages()
				assertTrue(
					reacquiredAfterRelease != null,
					"the module images must re-acquire after releaseImages",
				)
				assertEquals(
					FAIL_INVALID_PARAMETER,
					bridge.presentHandoff(),
					"a handoff after release and re-acquire must stay refused while the tag " +
						"records are stale",
				)
				// Re-recording only the SR side stays refused: the FG side of the set the
				// release invalidated is still stale, and a partial re-record can never revive
				// the handoff on its own - the same per-side freshness the consumed case proves.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagSrResources(
						SrTagRequest(
							frame.address(),
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
					"the post-release SR tag must record on the re-acquired images",
				)
				assertEquals(
					FAIL_INVALID_PARAMETER,
					bridge.presentHandoff(),
					"a handoff must stay refused after the release while only the SR side " +
						"re-recorded and the FG side is still stale",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagFgResources(
						FgTagRequest(
							frame.address(),
							ImageBinding(
								depth.view(),
								depth.image(),
								VK10.VK_FORMAT_D32_SFLOAT,
							),
							ImageBinding(
								hudless.view(),
								hudless.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							),
							ImageBinding(
								ui.view(),
								ui.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							),
						),
					),
					"the post-release FG tag must record under the fresh frame token",
				)
				val postReleaseIndexes = bridge.taggedFrameIndexes()
				assertEquals(
					postReleaseIndexes.srFrameIndex,
					postReleaseIndexes.fgFrameIndex,
					"the post-release tags must record under one equal frame index, got " +
						"SR=${postReleaseIndexes.srFrameIndex}, FG=${postReleaseIndexes.fgFrameIndex}",
				)
				assertTrue(
					postReleaseIndexes.srFrameIndex != preReleaseIndexes.srFrameIndex,
					"releaseImages must drop the retained token so the post-release tags advance " +
						"the frame, got the pre-release index ${preReleaseIndexes.srFrameIndex} again",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.presentHandoff(),
					"the post-release complete tag set must hand off once",
				)

				// A configuration replacement invalidates the frame eligibility: the tag set
				// above was recorded under the previous configuration, and a handoff for it must
				// be refused as stale once the configuration that interpreted it is replaced.
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
					"a configuration replacement must re-record the SR options",
				)
				assertEquals(
					FAIL_INVALID_PARAMETER,
					bridge.presentHandoff(),
					"a handoff after a configuration replacement must answer FAIL_InvalidParameter " +
						"while the tag records are stale",
				)
				// Re-recording the FG options alone does not revive the stale records: the
				// eligibility clears with the configuration, and only a fresh tag set hands off.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.configureFg(3),
					"the FG options must re-record for the replaced configuration",
				)
				assertEquals(
					FAIL_INVALID_PARAMETER,
					bridge.presentHandoff(),
					"a handoff for the pre-replacement tag set must stay refused after the FG " +
						"options re-record",
				)

				// Reset releases the images and invalidates the records: a handoff is refused
				// both while the images are gone and after they re-acquire without re-tagging,
				// so a tag set recorded before the reset can never satisfy a handoff once the
				// images exist again.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.reset(),
					"reset must succeed on a ready session",
				)
				assertEquals(
					FAIL_INVALID_PARAMETER,
					bridge.presentHandoff(),
					"a handoff after reset must answer FAIL_InvalidParameter while the tag records " +
						"are stale",
				)
				val reacquired = bridge.acquireImages()
				assertTrue(reacquired != null, "the module images must re-acquire after reset")
				assertEquals(
					FAIL_INVALID_PARAMETER,
					bridge.presentHandoff(),
					"a handoff after reset and re-acquire must stay refused until both tags " +
						"re-record",
				)

				// The post-reset tag set records under a fresh token: reset dropped the retained
				// token, so the new tags advance the frame, and the re-armed complete set hands
				// off once more.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagSrResources(
						SrTagRequest(
							frame.address(),
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
					"the post-reset SR tag must record on the caller's command buffer",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagFgResources(
						FgTagRequest(
							frame.address(),
							ImageBinding(
								depth.view(),
								depth.image(),
								VK10.VK_FORMAT_D32_SFLOAT,
							),
							ImageBinding(
								hudless.view(),
								hudless.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							),
							ImageBinding(
								ui.view(),
								ui.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							),
						),
					),
					"the post-reset FG tag must record under the fresh frame token",
				)
				val postResetIndexes = bridge.taggedFrameIndexes()
				assertEquals(
					postResetIndexes.srFrameIndex,
					postResetIndexes.fgFrameIndex,
					"the post-reset tags must record under one equal fresh frame index, got " +
						"SR=${postResetIndexes.srFrameIndex}, FG=${postResetIndexes.fgFrameIndex}",
				)
				assertTrue(
					postResetIndexes.srFrameIndex != indexes.srFrameIndex,
					"reset must drop the retained token so the post-reset tags advance the frame, " +
						"got the pre-reset index ${indexes.srFrameIndex} again",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.presentHandoff(),
					"the post-reset complete tag set must hand off once",
				)
				fixture.endSubmitAndWait(frame)
				// The final frame's tags reference the post-reset images, not the first
				// acquisition, so the clean check asserts on the handles the submitted frame
				// actually tagged.
				assertHandoffFrameClean(fixture, color, depth, hudless, ui, reacquired!!)
			}
		}
	}

	@Test
	fun `native source contains no direct DLSS-G evaluation call`() {
		// Every native translation unit and header, walked rather than enumerated so a new
		// unit cannot silently reintroduce the direct-evaluate path.
		val nativeFiles = Files.walk(Path.of("native"))
			.filter { Files.isRegularFile(it) }
			.filter { it.toString().endsWith(".cpp") || it.toString().endsWith(".h") }
			.toList()

		// A direct DLSS-G evaluation necessarily names both the call and the feature in one
		// translation unit, whatever line the argument lands on. Each token alone is
		// legitimate: kFeatureDLSS_G appears in the bootstrap feature list, and
		// slEvaluateFeature is the SR evaluation. Only their coexistence is the pre-v8
		// direct-evaluate regression.
		val offenders = nativeFiles.filter { file ->
			val source = Files.readString(file)
			source.contains("kFeatureDLSS_G") && source.contains("slEvaluateFeature")
		}
		assertTrue(
			offenders.isEmpty(),
			"native code must not call slEvaluateFeature(kFeatureDLSS_G): the pinned " +
				"Streamline 2.12.0 plugin answers eErrorMissingOrInvalidAPI, and a caller that " +
				"maps that error to success (as the invalidated fg-evaluate-records slice did) " +
				"records a frame DLSS-G silently never generates - a green suite with nothing " +
				"generated, visible only at present-time acceptance: $offenders",
		)
	}

	@Test
	fun `adapter gates present handoff on READY and latches failures`() {
		val native = FakeNative()
		val outputDimensions = Dimensions(2560, 1440)
		val session = DlssSession(config(outputDimensions))
		val adapter = LifecycleAdapter(session, native)

		// Not ready yet: the handoff must not reach the bridge.
		assertFalse(adapter.presentHandoff(), "a session that is not READY must not hand off")
		assertEquals(0, native.presentHandoffCalls)

		// Ready: initialize arms the session, and the handoff crosses to the bridge.
		assertTrue(
			adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")) != null,
			"initialize must bring the session to READY",
		)
		assertTrue(adapter.presentHandoff(), "a READY session must record the present handoff")
		assertEquals(1, native.presentHandoffCalls)

		// A refused handoff latches the session under the present-handoff stage, exactly like
		// any other native stage: a frame that cannot hand off must not present as if it could.
		native.presentHandoffResult = NativeApi.SUCCESS_RESULT + 1
		assertFalse(adapter.presentHandoff(), "a refused present handoff must latch the session")
		assertEquals(DlssSessionState.FALLBACK_LATCHED, session.state)
		assertEquals(DlssNativeStage.PRESENT_HANDOFF, session.failure?.stage)
	}

	/**
	 * Asserts the rung's validation oracle was actually running, then that the Khronos
	 * validation layer reported no errors naming any of the frame's six tagged images - the
	 * engine's colour, depth, HUD-less, and UI targets, and the module's motion and output
	 * images.
	 *
	 * Validation is the only oracle for image layouts: a handoff that recorded a transition
	 * or a relabel the images' actual layouts cannot serve is undefined behaviour to the
	 * driver and silent without it, so a session whose layer could not be enabled has no
	 * evidence worth asserting on and the rung FAILS rather than silently skipping the clean
	 * check.
	 */
	private fun assertHandoffFrameClean(
		fixture: HeadlessVulkanFixture,
		color: HeadlessVulkanFixture.EngineImage,
		depth: HeadlessVulkanFixture.EngineImage,
		hudless: HeadlessVulkanFixture.EngineImage,
		ui: HeadlessVulkanFixture.EngineImage,
		images: EvaluationImages,
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
			"the handed-off frame must not leave a resource in a state validation rejects: $errors",
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

	private fun config(outputDimensions: Dimensions) = DlssStartupConfig(
		enabled = true,
		qualityMode = SRMode.QUALITY,
		outputDimensions = outputDimensions,
		sdkPath = null,
		nativeLibraryPath = null,
		dataPath = null,
		warnings = emptyList(),
	)

	/**
	 * Records the present-handoff seam and answers the three calls [LifecycleAdapter.initialize]
	 * drives; everything else is a call this test never makes.
	 */
	private class FakeNative : NativeApi {
		var presentHandoffResult = NativeApi.SUCCESS_RESULT
		var presentHandoffCalls = 0

		override fun presentHandoff(): Int {
			presentHandoffCalls++
			return presentHandoffResult
		}

		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		): Int = NativeApi.SUCCESS_RESULT

		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int) =
			Dimensions(1280, 720)

		override fun configure(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		): Int = NativeApi.SUCCESS_RESULT

		override fun acquireImages(): EvaluationImages = error("unexpected acquireImages")
		override fun releaseImages(): Int = error("unexpected releaseImages")
		override fun waitDeviceIdle(): Int = error("unexpected waitDeviceIdle")
		override fun frameTimings(): FrameTimings? = error("unexpected frameTimings")
		override fun writeMotion(request: MotionRequest): Int = error("unexpected writeMotion")
		override fun presentOutput(target: PresentTarget): Int = error("unexpected presentOutput")
		override fun evaluate(request: EvaluationRequest): Int = error("unexpected evaluate")
	}

	private companion object {
		/** NVSDK_NGX_Result_FAIL_NotInitialized = NVSDK_NGX_Result_Fail | 7 (0xBAD00000 | 7). */
		const val FAIL_NOT_INITIALIZED = 0xBAD00007.toInt()

		/** NVSDK_NGX_Result_FAIL_InvalidParameter = NVSDK_NGX_Result_Fail | 5 (0xBAD00000 | 5). */
		const val FAIL_INVALID_PARAMETER = 0xBAD00005.toInt()
	}
}
