package me.snowmii.dlss.fg

import java.nio.file.Path
import me.snowmii.dlss.NativeBridge
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.dlss.bridge.ExtensionBootstrap
import me.snowmii.streamline.FgTagRequest
import me.snowmii.dlss.bridge.HeadlessVulkanFixture
import me.snowmii.streamline.ImageBinding
import me.snowmii.streamline.Native
import me.snowmii.streamline.NativeApi
import me.snowmii.streamline.NativeException
import me.snowmii.streamline.PresentMarkerEvent
import me.snowmii.streamline.PresentMarkerEvents
import me.snowmii.streamline.PresentMarkerType
import me.snowmii.streamline.SrTagRequest
import me.snowmii.streamline.Vec2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.lwjgl.vulkan.VK10

/**
 * M-11 present-marker rung: a successful present handoff emits exactly one PRESENT_START then
 * PRESENT_END under the same Streamline frame index the frame's SR/FG tags - and its common
 * constants - recorded under, and refused or pre-ready handoffs emit no markers at all.
 *
 * The seam is [NativeApi.presentHandoff]: the DLSS-G guide requires the frame index carried by
 * the Reflex present markers to match the frame index carried by the common constants
 * ("Make sure that frame index provided with the common constants is matching the presented
 * frame (i.e. frame index provided with Reflex markers ReflexMarker::ePresentStart and
 * ReflexMarker::ePresentEnd)"), and the native handoff emits both markers against the same
 * retained frame token the SR/FG tags and slSetConstants used - the DLSS-G plugin correlates
 * the presented frame with its constants through PRESENT_START and disables generation for
 * the frame without it.
 *
 * The frame is composed exactly like the production present path ([PresentIntegrationTest]):
 * the FG tag records first and obtains the retained frame token, the SR tag records second
 * under that same token, the SR evaluation records third - the common-constants path
 * (slSetConstants) and the feature evaluation run against the retained token, so the frame
 * index the markers must match is the one the constants actually used - and the FG tag
 * re-records fourth under the same token so the present path reads the engine-resting
 * declarations. Only then does the handoff emit the bracket.
 *
 * The markers themselves land inside sl.pcl.dll and cannot be read back, so the proof runs
 * through the native oracle `mc_dlss_query_present_markers` ([NativeApi.presentMarkers]): the
 * per-type counts of actually-emitted START and END markers, and the recent event log in
 * emission order, each event naming its marker type and the frame index it was emitted under.
 * Equality of the events' frame indexes with [me.snowmii.streamline.TaggedFrameIndexes] is
 * the same-index proof (the handoff's own gates already proved the two tag sides equal, and
 * each marker's index must equal that shared index), the log's START-then-END order is the
 * bracket-order proof, and each count advancing by exactly one per successful handoff - and
 * never across a refusal - is the exactly-once proof. Before any marker was emitted the
 * oracle refuses, which is what makes the refused and pre-ready no-marker halves of the
 * invariant observable.
 *
 * Like the other live FG rungs, the whole scenario runs in ONE test method (and therefore one
 * test fork): the close-path slShutdown is what makes the fork's exit clean, and a fork that
 * followed an unclean exit comes up with the plugin manager already initialized.
 */
@NativeBridge
class FgPresentMarkersTest {

	@Test
	fun `one live session emits exactly one PRESENT_START then PRESENT_END per successful handoff under the composed frame token`(
		@TempDir dataPath: Path,
	) {
		// Pre-init: this fork's module has never bootstrapped and never emitted a marker,
		// so the handoff refuses and the oracle has nothing to answer with - the pre-ready
		// no-marker half of the invariant, checked on a throwaway bridge against the
		// module's own state.
		Native.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
			assertEquals(
				FAIL_NOT_INITIALIZED,
				bridge.presentHandoff(),
				"presentHandoff before bootstrap must answer FAIL_NotInitialized",
			)
			// Pre-ready, the present calls stay refusals like the handoff: the no-op contract
			// covers the armed/unarmed present of a READY session, not a session that never
			// bootstrapped.
			assertEquals(
				FAIL_NOT_INITIALIZED,
				bridge.presentStart(),
				"presentStart before bootstrap must answer FAIL_NotInitialized",
			)
			assertEquals(
				FAIL_NOT_INITIALIZED,
				bridge.presentEnd(),
				"presentEnd before bootstrap must answer FAIL_NotInitialized",
			)
			assertEquals(
				null,
				bridge.presentMarkersOrNull(),
				"the present-marker oracle before any handoff must have no event to report",
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
			false,
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
				// MaxQuality = 2 (NVSDK_NGX_PerfQuality_Value); preset K = 11.
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
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.configureFg(3),
					"the FG options must record for the stored configuration",
				)

				// Recording options emits nothing: the oracle must still refuse after a
				// ready session whose only activity was the option records.
				assertEquals(
					null,
					bridge.presentMarkersOrNull(),
					"recording the FG options must not emit present markers",
				)

				// An unarmed present - the SR-only or skipped present of a session that never
				// handed off - must no-op: the present mixin fires on every present, and a
				// refusal here would latch the session on a frame that simply did not compose.
				// Both calls succeed, neither emits a marker, and the oracle stays silent.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.presentStart(),
					"an unarmed presentStart must no-op instead of refusing",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.presentEnd(),
					"an unarmed presentEnd must no-op instead of refusing",
				)
				assertEquals(
					null,
					bridge.presentMarkersOrNull(),
					"unarmed present calls must not emit present markers",
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

				// The engine's render-sized colour and depth and its output-sized HUD-less and
				// UI targets, standing in for Minecraft's main target, depth texture, and the
				// split's two targets.
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

				val frame = fixture.allocateAndBeginCommandBuffer()

				// Refused handoffs emit nothing: this handoff has no tag set at all, and the
				// oracle must stay as silent as before the refusal.
				assertEquals(
					FAIL_INVALID_PARAMETER,
					bridge.presentHandoff(),
					"a handoff before any tag recorded must answer FAIL_InvalidParameter",
				)
				assertEquals(
					null,
					bridge.presentMarkersOrNull(),
					"a refused handoff must not emit present markers",
				)

				// One frame, one buffer, one shared frame token, composed exactly like the
				// production present path: the FG tag records first and obtains the retained
				// token, the SR tag records second under that same token, the SR evaluation
				// records the frame's common constants (slSetConstants) and runs the feature
				// third against the retained token, and the FG tag re-records fourth under it
				// so the present path reads the engine-resting declarations. The evaluation is
				// what makes the marker correlation real: the guide's same-frame-index rule
				// names the common constants, and this is the frame that actually recorded
				// them under the token the markers are emitted under.
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
					"the FG tag must record first and obtain the retained frame token",
				)
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
					"the SR tag must record on the same buffer under the FG tag's retained frame token",
				)
				val firstIndexes = bridge.taggedFrameIndexes()
				assertEquals(
					firstIndexes.srFrameIndex,
					firstIndexes.fgFrameIndex,
					"the SR and FG tags of one frame must record under the same Streamline " +
						"frame index, got SR=${firstIndexes.srFrameIndex}, FG=${firstIndexes.fgFrameIndex}",
				)

				// Tagging emits nothing either: the marker bracket belongs to the handoff
				// seam, so the oracle must still refuse between the tags and the handoff.
				assertEquals(
					null,
					bridge.presentMarkersOrNull(),
					"tagging the frame's resources must not emit present markers",
				)

				// The SR evaluation: the production common-constants path records the frame's
				// slSetConstants under the retained token and runs slEvaluateFeature on the
				// shared buffer, exactly as PresentIntegrationTest composes the frame. The
				// guide's correlation rule names these constants, so they must be on the frame
				// whose markers the handoff emits - a frame that never evaluated would leave
				// the same-token claim unexercised.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.evaluate(
						EvaluationRequest.builder()
							.commandBuffer(frame.address())
							.color(ImageBinding(
								color.view(),
								color.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							))
							.depth(ImageBinding(
								depth.view(),
								depth.image(),
								VK10.VK_FORMAT_D32_SFLOAT,
							))
							.jitter(Vec2(0.25f, -0.5f))
							.motionScale(Vec2(1f, 1f))
							.frameTimeMilliseconds(16.6f)
							.resetHistory(true)
							.renderDimensions(dimensions)
							.build(),
					),
					"the SR evaluation must record the common constants and the feature call on the tagged frame's shared buffer",
				)
				// The final FG re-tag, after the evaluation and under the SAME retained token:
				// the evaluation kept the token on the composed frame, so the re-declaration
				// lands on the same frame index and hands the present path the engine-resting
				// declarations, exactly like the production frame PresentIntegrationTest pins.
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
					"the FG tag must re-record after the evaluation under the same retained frame token",
				)
				assertEquals(
					null,
					bridge.presentMarkersOrNull(),
					"the evaluation and the FG re-tag must not emit present markers",
				)

				// The complete equal-index tag set hands off, and the handoff emits the
				// marker bracket: exactly one PRESENT_START then PRESENT_END, under the frame
				// index the tags and the common constants recorded under.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.presentHandoff(),
					"the complete equal-index tag set must arm once",
				)
				assertEquals(NativeApi.SUCCESS_RESULT, bridge.presentStart())
				// A second START for the same bracket - a present that threw between START and
				// END - must no-op: the bracket is already open, and a second marker for the
				// same frame would corrupt the correlation. The marker assertions below would
				// catch a leaked second event.
				assertEquals(NativeApi.SUCCESS_RESULT, bridge.presentStart())
				assertEquals(NativeApi.SUCCESS_RESULT, bridge.presentEnd())
				val firstMarkers = bridge.presentMarkers()
				assertEquals(
					1,
					firstMarkers.startCount,
					"the first successful handoff must emit exactly one PRESENT_START",
				)
				assertEquals(
					1,
					firstMarkers.endCount,
					"the first successful handoff must emit exactly one PRESENT_END",
				)
				assertEquals(
					2,
					firstMarkers.eventCount,
					"the first successful handoff must emit exactly two marker events in total",
				)
				assertEquals(
					listOf(
						PresentMarkerEvent(PresentMarkerType.PRESENT_START, firstIndexes.srFrameIndex),
						PresentMarkerEvent(PresentMarkerType.PRESENT_END, firstIndexes.srFrameIndex),
					),
					firstMarkers.events,
					"the first successful handoff must emit PRESENT_START then PRESENT_END, in " +
						"that order, under the frame index the tags and the common constants " +
						"recorded under",
				)

				// Consumed: the frame's tag set already handed off, and the refusal must not
				// emit anything - the oracle keeps answering the previous events unchanged.
				assertEquals(
					FAIL_INVALID_PARAMETER,
					bridge.presentHandoff(),
					"a second handoff for the same tag set must answer FAIL_InvalidParameter",
				)
				assertEquals(
					firstMarkers,
					bridge.presentMarkers(),
					"a refused handoff must not emit another marker event",
				)

				// The re-recorded complete set hands off under a fresh token: the handoff
				// consumed the first frame's token, so the re-recorded tags advance the frame
				// and the second successful handoff emits its bracket under the new index -
				// the counts prove each handoff added exactly one START and one END, and the
				// events prove each bracket tracked its own frame.
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
				val secondIndexes = bridge.taggedFrameIndexes()
				assertEquals(
					secondIndexes.srFrameIndex,
					secondIndexes.fgFrameIndex,
					"the re-recorded tags must record under one equal fresh frame index, got " +
						"SR=${secondIndexes.srFrameIndex}, FG=${secondIndexes.fgFrameIndex}",
				)
				assertTrue(
					secondIndexes.srFrameIndex != firstIndexes.srFrameIndex,
					"a consumed handoff must drop the retained token so the re-recorded tags " +
						"advance the frame, got the first index ${firstIndexes.srFrameIndex} again",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.evaluate(
						EvaluationRequest.builder()
							.commandBuffer(frame.address())
							.color(ImageBinding(
								color.view(),
								color.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							))
							.depth(ImageBinding(
								depth.view(),
								depth.image(),
								VK10.VK_FORMAT_D32_SFLOAT,
							))
							.jitter(Vec2(0.25f, -0.5f))
							.motionScale(Vec2(1f, 1f))
							.frameTimeMilliseconds(16.6f)
							.resetHistory(true)
							.renderDimensions(dimensions)
							.build(),
					),
					"the re-recorded frame's SR evaluation must record under the fresh retained token",
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
					"the re-recorded frame's FG tag must re-record after its evaluation",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.presentHandoff(),
					"the re-recorded complete tag set must arm once",
				)
				assertEquals(NativeApi.SUCCESS_RESULT, bridge.presentStart())
				assertEquals(NativeApi.SUCCESS_RESULT, bridge.presentEnd())
				val secondMarkers = bridge.presentMarkers()
				assertEquals(
					2,
					secondMarkers.startCount,
					"the second successful handoff must emit exactly one more PRESENT_START",
				)
				assertEquals(
					2,
					secondMarkers.endCount,
					"the second successful handoff must emit exactly one more PRESENT_END",
				)
				assertEquals(
					4,
					secondMarkers.eventCount,
					"two successful handoffs must emit exactly four marker events in total",
				)
				assertEquals(
					listOf(
						PresentMarkerEvent(PresentMarkerType.PRESENT_START, firstIndexes.srFrameIndex),
						PresentMarkerEvent(PresentMarkerType.PRESENT_END, firstIndexes.srFrameIndex),
						PresentMarkerEvent(PresentMarkerType.PRESENT_START, secondIndexes.srFrameIndex),
						PresentMarkerEvent(PresentMarkerType.PRESENT_END, secondIndexes.srFrameIndex),
					),
					secondMarkers.events,
					"the second successful handoff must emit exactly one more PRESENT_START then " +
						"PRESENT_END under the re-recorded frame index",
				)
				// The bracket invariant read pairwise: every bracket in the log is START then
				// END under one frame index, and no bracket ever spans two tokens.
				assertTrue(
					secondMarkers.events.chunked(2).all { (start, end) ->
						start.type == PresentMarkerType.PRESENT_START &&
							end.type == PresentMarkerType.PRESENT_END &&
							start.frameIndex == end.frameIndex
					},
					"every bracket in the event log must be PRESENT_START then PRESENT_END under " +
						"one frame index: ${secondMarkers.events}",
				)

				// The END-only-after-START discipline: a third complete set hands off, and the
				// END arrives without a START. The END must not emit a marker for a bracket no
				// START opened - the log stays exactly at the second bracket - and must still
				// consume the armed bracket, so the START and END that follow the consumed
				// present no-op instead of opening a stale bracket under a stale token.
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
					"the third frame's FG tag must re-record under a fresh token after the consumed bracket",
				)
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
					"the third frame's SR tag must record after its FG tag",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.evaluate(
						EvaluationRequest.builder()
							.commandBuffer(frame.address())
							.color(ImageBinding(
								color.view(),
								color.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							))
							.depth(ImageBinding(
								depth.view(),
								depth.image(),
								VK10.VK_FORMAT_D32_SFLOAT,
							))
							.jitter(Vec2(0.25f, -0.5f))
							.motionScale(Vec2(1f, 1f))
							.frameTimeMilliseconds(16.6f)
							.resetHistory(true)
							.renderDimensions(dimensions)
							.build(),
					),
					"the third frame's SR evaluation must record under the fresh retained token",
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
					"the third frame's FG tag must re-record after its evaluation",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.presentHandoff(),
					"the third complete tag set must arm once",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.presentEnd(),
					"an END without a START must no-op and consume the armed bracket",
				)
				assertEquals(
					secondMarkers,
					bridge.presentMarkers(),
					"an END without a START must not emit a marker for a bracket no START opened",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.presentStart(),
					"a consumed bracket's START must no-op",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.presentEnd(),
					"a consumed bracket's END must no-op",
				)
				assertEquals(
					secondMarkers,
					bridge.presentMarkers(),
					"a consumed bracket must emit nothing for later presents",
				)
				fixture.endSubmitAndWait(frame)
			}
		}
	}

	/**
	 * Answers the oracle when it has events to report, or null when it refuses with
	 * FAIL_NotInitialized - the state every pre-handoff and refused-handoff checkpoint
	 * asserts.
	 */
	private fun Native.presentMarkersOrNull(): PresentMarkerEvents? = try {
		presentMarkers()
	} catch (error: NativeException) {
		if (error.resultCode() == FAIL_NOT_INITIALIZED) {
			null
		} else {
			throw error
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

	private companion object {
		/** NVSDK_NGX_Result_FAIL_NotInitialized = NVSDK_NGX_Result_Fail | 7 (0xBAD00000 | 7). */
		const val FAIL_NOT_INITIALIZED = 0xBAD00007.toInt()

		/** NVSDK_NGX_Result_FAIL_InvalidParameter = NVSDK_NGX_Result_Fail | 5 (0xBAD00000 | 5). */
		const val FAIL_INVALID_PARAMETER = 0xBAD00005.toInt()
	}
}
