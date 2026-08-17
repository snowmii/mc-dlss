package me.snowmii.dlss.fg

import java.nio.file.Path
import me.snowmii.streamline.NativeApiTestDouble
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.EvaluationImages
import me.snowmii.streamline.FrameTimings
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.streamline.ExtensionBootstrap
import me.snowmii.streamline.FgTagRequest
import me.snowmii.dlss.bridge.HeadlessVulkanFixture
import me.snowmii.streamline.ImageBinding
import me.snowmii.streamline.MotionRequest
import me.snowmii.streamline.Native
import me.snowmii.streamline.NativeApi
import me.snowmii.streamline.NativeException
import me.snowmii.streamline.PresentMarkerEvent
import me.snowmii.streamline.PresentMarkerType
import me.snowmii.streamline.PresentTarget
import me.snowmii.streamline.SrTagRequest
import me.snowmii.streamline.Vec2
import me.snowmii.dlss.render.FrameEvaluation
import me.snowmii.dlss.render.WorldPhase
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssSessionState
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.session.SRMode
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.lwjgl.vulkan.VK10
import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import me.snowmii.dlss.NativeBridge

/**
 * Reflex-marker behavior: each READY frame emits ordered simulation and render-submit
 * markers through one retained Streamline frame token shared with the frame's SR/FG tags,
 * common constants, and present markers; the input seam obtains that token and runs the
 * unconditional slReflexSleep; refused sessions emit none.
 *
 * The live half runs one real Streamline session on the headless Vulkan fixture and composes
 * one frame exactly like production: the input seam at GLFW poll obtains the frame's token
 * and runs Reflex sleep, the simulation pair fires, the FG/SR tags and SR evaluation record
 * under the same retained token, the render-submit pair fires around the command-encoder
 * submit, and the present bracket closes under the same token. The proof runs through the
 * native oracle `mc_dlss_query_reflex_markers` ([NativeApi.reflexMarkers]): the per-type
 * counts of actually-emitted markers and the recent event log in emission order, each event
 * naming its marker type and the frame index it was emitted under. Equality of the events'
 * frame indexes with [me.snowmii.streamline.TaggedFrameIndexes] (and with the present
 * markers' indexes) is the one-token proof, the log order is the bracket-order proof, and
 * the counts advancing by exactly one per emitted marker - and never across a refusal - is
 * the emit-none proof. Before any marker was emitted the oracle refuses, which is what makes
 * the refused and pre-ready no-marker halves observable.
 *
 * `ePCLatencyPing` is deliberately absent: the PCL guide requires it only in response to
 * `PclState::statsWindowMessage`. Emitting one every frame corrupts PC-latency statistics.
 *
 * Like the other live FG tests, the whole live scenario runs in ONE test method (and
 * therefore one test fork): the close-path slShutdown is what makes the fork's exit clean,
 * and a fork that followed an unclean exit comes up with the plugin manager already
 * initialized.
 */
@NativeBridge
class ReflexMarkersTest {

	@Test
	fun `one live session emits ordered simulation and render-submit markers per READY frame under the one shared frame token`(
		@TempDir dataPath: Path,
	) {
		// Pre-init: this fork's module has never bootstrapped and never emitted a marker, so
		// every marker entry refuses and the oracle has nothing to answer with - the
		// pre-ready no-marker half of the invariant, checked on a throwaway bridge against
		// the module's own state.
		Native.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
			assertEquals(
				FAIL_NOT_INITIALIZED,
				bridge.reflexInputSample(),
				"reflexInputSample before bootstrap must answer FAIL_NotInitialized",
			)
			assertEquals(
				FAIL_NOT_INITIALIZED,
				bridge.reflexMarker(NativeApi.ReflexMarkerType.SIMULATION_START),
				"the SIMULATION_START marker before bootstrap must answer FAIL_NotInitialized",
			)
			assertEquals(
				FAIL_NOT_INITIALIZED,
				bridge.reflexMarker(NativeApi.ReflexMarkerType.SIMULATION_END),
				"the SIMULATION_END marker before bootstrap must answer FAIL_NotInitialized",
			)
			assertEquals(
				FAIL_NOT_INITIALIZED,
				bridge.reflexMarker(NativeApi.ReflexMarkerType.RENDER_SUBMIT_START),
				"the RENDER_SUBMIT_START marker before bootstrap must answer FAIL_NotInitialized",
			)
			assertEquals(
				FAIL_NOT_INITIALIZED,
				bridge.reflexMarker(NativeApi.ReflexMarkerType.RENDER_SUBMIT_END),
				"the RENDER_SUBMIT_END marker before bootstrap must answer FAIL_NotInitialized",
			)
			assertEquals(
				null,
				bridge.reflexMarkersOrNull(),
				"the reflex-marker oracle before any marker must have no event to report",
			)
			assertEquals(
				null,
				bridge.reflexRegistrationOrNull(),
				"the reflex options oracle before bootstrap must have no registration to report",
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
				val dimensions = bridge.queryOptimalDimensions(outputWidth, outputHeight, 2)
				assertTrue(
					dimensions.width in 1..outputWidth && dimensions.height in 1..outputHeight,
					"queried render dimensions must be in (0, output], got $dimensions",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.configureSuperResolution(outputWidth, outputHeight, dimensions.width, dimensions.height, 2, 11),
					"configure must record the SR options for the stored configuration",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.configureFg(3),
					"the FG options must record for the stored configuration",
				)

				// Recording options emits no markers: the oracle must still refuse after a
				// ready session whose only activity was the option records.
				assertEquals(
					null,
					bridge.reflexMarkersOrNull(),
					"recording the options must not emit reflex markers",
				)

				// The Reflex options register only at the READY transition: a ready session
				// that has not initialized yet has no registration to report.
				assertEquals(
					null,
					bridge.reflexRegistrationOrNull(),
					"the reflex options oracle must refuse until the READY transition records",
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
				// The READY transition registered the Reflex options exactly once: eLowLatency
				// recorded, one slReflexSetOptions call.
				assertEquals(
					NativeApi.ReflexRegistration(LOW_LATENCY, 1),
					bridge.queryReflexOptions(),
					"initialize must register Reflex eLowLatency options exactly once",
				)
				// The idempotent repeat of the recorded tuple must not re-register: the
				// exactly-once discipline lives on the transition, not on the repeat path.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.initialize(
						fixture.instanceAddress(),
						fixture.physicalDeviceAddress(),
						fixture.deviceAddress(),
						dataPath,
						dataPath,
					),
					"re-initializing with the recorded tuple must succeed without re-recording",
				)
				assertEquals(
					NativeApi.ReflexRegistration(LOW_LATENCY, 1),
					bridge.queryReflexOptions(),
					"the idempotent re-initialize must not call slReflexSetOptions again",
				)
				val images = bridge.acquireImages()
				assertTrue(images != null, "module images must be acquired before the frame")

				// The engine's render-sized colour and depth and its output-sized HUD-less
				// and UI targets, standing in for Minecraft's main target, depth texture,
				// and the split's two targets.
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

				// A frame that never ran its input sample has no token: a simulation marker
				// before the input sample must refuse and emit nothing - the same refusal a
				// pre-ready session answers, so the oracle stays silent either way.
				assertEquals(
					FAIL_NOT_INITIALIZED,
					bridge.reflexMarker(NativeApi.ReflexMarkerType.SIMULATION_START),
					"a simulation marker before the frame's input sample must answer FAIL_NotInitialized",
				)
				assertEquals(
					null,
					bridge.reflexMarkersOrNull(),
					"a refused simulation marker must not emit anything",
				)

				// Frame 1, composed exactly like production: the input-sample seam obtains
				// the frame's token (and runs the unconditional sleep), the simulation pair
				// fires before the render, the FG tag and SR tag record under the retained
				// token, the SR evaluation records the common constants under it, the FG tag
				// re-records for the present path, the handoff arms the present bracket, the
				// render-submit pair fires around the submission, and the present bracket
				// closes under the same token.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.reflexInputSample(),
					"the input sample must obtain the frame's token and run Reflex sleep",
				)
				assertEquals(NativeApi.SUCCESS_RESULT, bridge.reflexMarker(NativeApi.ReflexMarkerType.SIMULATION_START))
				assertEquals(NativeApi.SUCCESS_RESULT, bridge.reflexMarker(NativeApi.ReflexMarkerType.SIMULATION_END))
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagFrameGenerationResources(
						FgTagRequest(
							frame.address(),
							ImageBinding(depth.view(), depth.image(), VK10.VK_FORMAT_D32_SFLOAT),
							ImageBinding(hudless.view(), hudless.image(), VK10.VK_FORMAT_R8G8B8A8_UNORM),
							ImageBinding(ui.view(), ui.image(), VK10.VK_FORMAT_R8G8B8A8_UNORM),
						),
					),
					"the FG tag must record under the input sample's retained frame token",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagSrResources(
						SrTagRequest(
							frame.address(),
							ImageBinding(color.view(), color.image(), VK10.VK_FORMAT_R8G8B8A8_UNORM),
							ImageBinding(depth.view(), depth.image(), VK10.VK_FORMAT_D32_SFLOAT),
						),
					),
					"the SR tag must record on the same buffer under the same retained frame token",
				)
				val firstIndexes = bridge.taggedFrameIndexes()
				assertEquals(
					firstIndexes.lastSrTagFrameIndex,
					firstIndexes.lastFgTagFrameIndex,
					"the SR and FG tags of one frame must record under the same Streamline frame index",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.evaluateSuperResolution(
						EvaluationRequest.builder()
							.commandBuffer(frame.address())
							.color(ImageBinding(color.view(), color.image(), VK10.VK_FORMAT_R8G8B8A8_UNORM))
							.depth(ImageBinding(depth.view(), depth.image(), VK10.VK_FORMAT_D32_SFLOAT))
							.jitter(Vec2(0.25f, -0.5f))
							.motionScale(Vec2(1f, 1f))
							.frameTimeMilliseconds(16.6f)
							.resetHistory(true)
							.renderDimensions(dimensions)
							.build(),
					),
					"the SR evaluation must record the common constants under the retained frame token",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagFrameGenerationResources(
						FgTagRequest(
							frame.address(),
							ImageBinding(depth.view(), depth.image(), VK10.VK_FORMAT_D32_SFLOAT),
							ImageBinding(hudless.view(), hudless.image(), VK10.VK_FORMAT_R8G8B8A8_UNORM),
							ImageBinding(ui.view(), ui.image(), VK10.VK_FORMAT_R8G8B8A8_UNORM),
						),
					),
					"the FG tag must re-record after the evaluation under the same retained token",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.recordPresentHandoff(),
					"the complete equal-index tag set must arm the present bracket once",
				)
				// The render-submit bracket fires around the actual command submission,
				// between the handoff and the present bracket, under the same retained token.
				assertEquals(NativeApi.SUCCESS_RESULT, bridge.reflexMarker(NativeApi.ReflexMarkerType.RENDER_SUBMIT_START))
				assertEquals(NativeApi.SUCCESS_RESULT, bridge.reflexMarker(NativeApi.ReflexMarkerType.RENDER_SUBMIT_END))
				assertEquals(NativeApi.SUCCESS_RESULT, bridge.presentStart())
				assertEquals(NativeApi.SUCCESS_RESULT, bridge.presentEnd())

				// The oracle: one simulation and render-submit pair, in production order, all
				// under the frame index the tags and common constants recorded under. The
				// latency-ping slot stays zero because no stats-window ping was received.
				val firstMarkers = bridge.reflexMarkers()
				assertArrayEquals(
					intArrayOf(0, 1, 1, 1, 1),
					firstMarkers.typeCounts(),
					"the first READY frame must emit frame markers but no unsolicited latency ping",
				)
				assertEquals(4, firstMarkers.eventCount(), "one frame must emit exactly four reflex marker events")
				assertEquals(
					listOf(
						NativeApi.ReflexMarkerEvent(NativeApi.ReflexMarkerType.SIMULATION_START, firstIndexes.lastSrTagFrameIndex),
						NativeApi.ReflexMarkerEvent(NativeApi.ReflexMarkerType.SIMULATION_END, firstIndexes.lastSrTagFrameIndex),
						NativeApi.ReflexMarkerEvent(NativeApi.ReflexMarkerType.RENDER_SUBMIT_START, firstIndexes.lastSrTagFrameIndex),
						NativeApi.ReflexMarkerEvent(NativeApi.ReflexMarkerType.RENDER_SUBMIT_END, firstIndexes.lastSrTagFrameIndex),
					),
					firstMarkers.events(),
					"one frame must emit SIMULATION_START then SIMULATION_END then " +
						"RENDER_SUBMIT_START then RENDER_SUBMIT_END under the frame's index",
				)
				// The present bracket of the same frame shares the same retained token: the
				// The present-marker oracle proves the frame's present markers correlate with the tags and
				// constants, and their index must equal the reflex markers' index - the whole
				// marker surface runs on one frame identity.
				assertEquals(
					listOf(
						PresentMarkerEvent(PresentMarkerType.PRESENT_START, firstIndexes.lastSrTagFrameIndex),
						PresentMarkerEvent(PresentMarkerType.PRESENT_END, firstIndexes.lastSrTagFrameIndex),
					),
					bridge.presentMarkers().events,
					"the frame's present markers must close under the same frame index as its reflex markers",
				)

				// The consumed frame has no retained token left: a render-submit marker for a
				// frame that never ran its input sample refuses and emits nothing, leaving the
				// oracle exactly where the first frame left it.
				assertEquals(
					FAIL_NOT_INITIALIZED,
					bridge.reflexMarker(NativeApi.ReflexMarkerType.RENDER_SUBMIT_START),
					"a render-submit marker without a retained token must answer FAIL_NotInitialized",
				)
				assertEquals(
					firstMarkers,
					bridge.reflexMarkers(),
					"a refused marker must not emit another event",
				)

				// Frame 2: the input sample obtains a fresh token - the frame identity
				// advances per frame, so one frame's markers can never be correlated with the
				// next frame's tags or presents.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.reflexInputSample(),
					"the second frame's input sample must obtain a fresh frame token",
				)
				assertEquals(NativeApi.SUCCESS_RESULT, bridge.reflexMarker(NativeApi.ReflexMarkerType.SIMULATION_START))
				assertEquals(NativeApi.SUCCESS_RESULT, bridge.reflexMarker(NativeApi.ReflexMarkerType.SIMULATION_END))
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagFrameGenerationResources(
						FgTagRequest(
							frame.address(),
							ImageBinding(depth.view(), depth.image(), VK10.VK_FORMAT_D32_SFLOAT),
							ImageBinding(hudless.view(), hudless.image(), VK10.VK_FORMAT_R8G8B8A8_UNORM),
							ImageBinding(ui.view(), ui.image(), VK10.VK_FORMAT_R8G8B8A8_UNORM),
						),
					),
					"the second frame's FG tag must record under its input sample's token",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagSrResources(
						SrTagRequest(
							frame.address(),
							ImageBinding(color.view(), color.image(), VK10.VK_FORMAT_R8G8B8A8_UNORM),
							ImageBinding(depth.view(), depth.image(), VK10.VK_FORMAT_D32_SFLOAT),
						),
					),
					"the second frame's SR tag must record under the same token",
				)
				val secondIndexes = bridge.taggedFrameIndexes()
				assertTrue(
					secondIndexes.lastSrTagFrameIndex != firstIndexes.lastSrTagFrameIndex,
					"each frame's input sample must advance the frame identity, got " +
						"${firstIndexes.lastSrTagFrameIndex} again",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.evaluateSuperResolution(
						EvaluationRequest.builder()
							.commandBuffer(frame.address())
							.color(ImageBinding(color.view(), color.image(), VK10.VK_FORMAT_R8G8B8A8_UNORM))
							.depth(ImageBinding(depth.view(), depth.image(), VK10.VK_FORMAT_D32_SFLOAT))
							.jitter(Vec2(0.25f, -0.5f))
							.motionScale(Vec2(1f, 1f))
							.frameTimeMilliseconds(16.6f)
							.resetHistory(true)
							.renderDimensions(dimensions)
							.build(),
					),
					"the second frame's SR evaluation must record under its own token",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagFrameGenerationResources(
						FgTagRequest(
							frame.address(),
							ImageBinding(depth.view(), depth.image(), VK10.VK_FORMAT_D32_SFLOAT),
							ImageBinding(hudless.view(), hudless.image(), VK10.VK_FORMAT_R8G8B8A8_UNORM),
							ImageBinding(ui.view(), ui.image(), VK10.VK_FORMAT_R8G8B8A8_UNORM),
						),
					),
					"the second frame's FG tag must re-record after its evaluation",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.recordPresentHandoff(),
					"the second frame's complete tag set must arm once",
				)
				assertEquals(NativeApi.SUCCESS_RESULT, bridge.reflexMarker(NativeApi.ReflexMarkerType.RENDER_SUBMIT_START))
				assertEquals(NativeApi.SUCCESS_RESULT, bridge.reflexMarker(NativeApi.ReflexMarkerType.RENDER_SUBMIT_END))
				assertEquals(NativeApi.SUCCESS_RESULT, bridge.presentStart())
				assertEquals(NativeApi.SUCCESS_RESULT, bridge.presentEnd())

				val secondMarkers = bridge.reflexMarkers()
				assertArrayEquals(
					intArrayOf(0, 2, 2, 2, 2),
					secondMarkers.typeCounts(),
					"the second READY frame must emit one more frame marker pair without a latency ping",
				)
				assertEquals(8, secondMarkers.eventCount(), "two frames must emit exactly eight reflex marker events")
				assertEquals(
					firstMarkers.events() + listOf(
						NativeApi.ReflexMarkerEvent(NativeApi.ReflexMarkerType.SIMULATION_START, secondIndexes.lastSrTagFrameIndex),
						NativeApi.ReflexMarkerEvent(NativeApi.ReflexMarkerType.SIMULATION_END, secondIndexes.lastSrTagFrameIndex),
						NativeApi.ReflexMarkerEvent(NativeApi.ReflexMarkerType.RENDER_SUBMIT_START, secondIndexes.lastSrTagFrameIndex),
						NativeApi.ReflexMarkerEvent(NativeApi.ReflexMarkerType.RENDER_SUBMIT_END, secondIndexes.lastSrTagFrameIndex),
					),
					secondMarkers.events(),
					"each frame's four markers must bracket under their own frame index, in order",
				)
				assertEquals(
					listOf(
						PresentMarkerEvent(PresentMarkerType.PRESENT_START, secondIndexes.lastSrTagFrameIndex),
						PresentMarkerEvent(PresentMarkerType.PRESENT_END, secondIndexes.lastSrTagFrameIndex),
					),
					bridge.presentMarkers().events.takeLast(2),
					"the second frame's present markers must close under its own frame index",
				)
				fixture.endSubmitAndWait(frame)

				// A reset drops the module's per-frame records but not its marker history: the
				// oracle is module history like the present-marker oracle, and only the
				// close-path reset_state clears it, which is what keeps the "refused sessions
				// emit none" half of the invariant observable on a fresh fork. The history
				// must keep answering exactly the events that reached the plugin.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.resetSuperResolutionHistory(),
					"reset must drop the module's per-frame records",
				)
				assertEquals(
					secondMarkers,
					bridge.reflexMarkers(),
					"the marker history must survive a per-frame reset and keep answering unchanged",
				)
				assertEquals(
					NativeApi.ReflexRegistration(LOW_LATENCY, 1),
					bridge.queryReflexOptions(),
					"two composed frames and a reset must leave the one READY registration untouched",
				)
			}
		}
	}

	@Test
	fun `the adapter gates the marker surface on READY and never latches a failed marker call`() {
		val calls = RecordingNativeApi()
		val session = session()
		val adapter = LifecycleAdapter(session, calls)
		// Not READY yet: every marker is refused before it reaches the native side.
		assertFalse(adapter.reflexInputSample(), "a non-READY session must refuse the input sample")
		assertFalse(adapter.reflexMarker(NativeApi.ReflexMarkerType.SIMULATION_START), "a non-READY session must refuse the simulation start")
		assertFalse(adapter.reflexMarker(NativeApi.ReflexMarkerType.SIMULATION_END), "a non-READY session must refuse the simulation end")
		assertFalse(adapter.reflexMarker(NativeApi.ReflexMarkerType.RENDER_SUBMIT_START), "a non-READY session must refuse the render-submit start")
		assertFalse(adapter.reflexMarker(NativeApi.ReflexMarkerType.RENDER_SUBMIT_END), "a non-READY session must refuse the render-submit end")
		assertTrue(calls.reflexCalls.isEmpty(), "refused markers must never reach the native side")

		// READY: every marker delegates and reports the native result.
		adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data"))
		assertEquals(DlssSessionState.READY, session.state)
		assertTrue(adapter.reflexInputSample(), "a READY session must emit the input sample")
		assertTrue(adapter.reflexMarker(NativeApi.ReflexMarkerType.SIMULATION_START), "a READY session must emit the simulation start")
		assertTrue(adapter.reflexMarker(NativeApi.ReflexMarkerType.SIMULATION_END), "a READY session must emit the simulation end")
		assertTrue(adapter.reflexMarker(NativeApi.ReflexMarkerType.RENDER_SUBMIT_START), "a READY session must emit the render-submit start")
		assertTrue(adapter.reflexMarker(NativeApi.ReflexMarkerType.RENDER_SUBMIT_END), "a READY session must emit the render-submit end")
		assertEquals(
			listOf("inputSample", "simulateStart", "simulateEnd", "renderSubmitStart", "renderSubmitEnd"),
			calls.reflexCalls,
			"the five marker entries must reach the native side in call order",
		)

		// A failed native marker call reports false without latching: the markers are the
		// PCL/Reflex diagnostic surface, not a frame-route stage, and a session that
		// rendered the frame anyway must keep rendering rather than degrade because a ping
		// did not reach the plugin.
		calls.failReflex = true
		assertFalse(adapter.reflexInputSample(), "a failed native marker call must report false")
		assertEquals(
			DlssSessionState.READY,
			session.state,
			"a failed marker call must never latch the session",
		)
		assertNull(session.failure, "a failed marker call must record no latched failure")
	}

	@Test
	fun `the world phase delegates the marker surface through the evaluation to the adapter in production order`() {
		val calls = RecordingNativeApi()
		val session = session()
		val adapter = LifecycleAdapter(session, calls)
		adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data"))
		val evaluation = FrameEvaluation(adapter, { null })

		// The world phase is the seam the Minecraft mixins call; a runtime carrying the
		// production evaluation is the wiring that connects it to the adapter.
		val phase = WorldPhase(
			runtime = me.snowmii.dlss.render.RenderRuntime(
				session = session,
				sceneTarget = me.snowmii.dlss.render.SceneTarget(
					allocate = { width, height -> HeadlessRenderTarget(width, height) },
					release = {},
				),
				startup = { render },
				frameEvaluation = evaluation,
			),
			present = { _, _ -> },
			onWorldTargetChanged = {},
		)

		assertTrue(phase.reflexInputSample(), "the world phase must delegate the input sample")
		assertTrue(phase.reflexMarker(NativeApi.ReflexMarkerType.SIMULATION_START), "the world phase must delegate the simulation start")
		assertTrue(phase.reflexMarker(NativeApi.ReflexMarkerType.SIMULATION_END), "the world phase must delegate the simulation end")
		assertTrue(phase.reflexMarker(NativeApi.ReflexMarkerType.RENDER_SUBMIT_START), "the world phase must delegate the render-submit start")
		assertTrue(phase.reflexMarker(NativeApi.ReflexMarkerType.RENDER_SUBMIT_END), "the world phase must delegate the render-submit end")
		assertEquals(
			listOf("inputSample", "simulateStart", "simulateEnd", "renderSubmitStart", "renderSubmitEnd"),
			calls.reflexCalls,
			"the five markers must reach the native side through the world phase in production order",
		)
	}

	/**
	 * Answers the oracle when it has events to report, or null when it refuses with
	 * FAIL_NotInitialized - the state every pre-ready and refused state asserts.
	 */
	private fun Native.reflexMarkersOrNull(): NativeApi.ReflexMarkerEvents? = try {
		reflexMarkers()
	} catch (error: NativeException) {
		if (error.resultCode() == FAIL_NOT_INITIALIZED) {
			null
		} else {
			throw error
		}
	}

	/** Answers the registration oracle when a record exists, or null when it refuses. */
	private fun Native.reflexRegistrationOrNull(): NativeApi.ReflexRegistration? = try {
		queryReflexOptions()
	} catch (error: NativeException) {
		if (error.resultCode() == FAIL_NOT_INITIALIZED || error.resultCode() == FAIL_INVALID_PARAMETER) {
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

	private val output = Dimensions(2560, 1440)
	private val render = Dimensions(1707, 960)

	private fun session() = DlssSession(
		DlssStartupConfig(
			enabled = true,
			qualityMode = SRMode.QUALITY,
			outputDimensions = output,
			sdkPath = null,
			nativeLibraryPath = null,
			dataPath = null,
			warnings = emptyList(),
		),
	)

	/**
	 * Records every reflex marker call in order so the delegation chain is assertable off the
	 * render thread; everything else the lifecycle needs answers success.
	 */
	private class RecordingNativeApi : NativeApiTestDouble() {
		val reflexCalls = mutableListOf<String>()
		var failReflex = false

		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		): Int = NativeApi.SUCCESS_RESULT

		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int): Dimensions =
			Dimensions(1707, 960)

		override fun configureSuperResolution(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		): Int = NativeApi.SUCCESS_RESULT

		override fun acquireImages(): EvaluationImages = EvaluationImages(
			ImageBinding(401L, 402L, 124),
			ImageBinding(501L, 502L, 37),
		)

		override fun releaseImages(): Int = NativeApi.SUCCESS_RESULT

		override fun waitDeviceIdle(): Int = NativeApi.SUCCESS_RESULT

		override fun frameTimings(): FrameTimings? = null

		override fun writeMotion(request: MotionRequest): Int = NativeApi.SUCCESS_RESULT

		override fun presentOutput(target: PresentTarget): Int = NativeApi.SUCCESS_RESULT

		override fun evaluateSuperResolution(request: EvaluationRequest): Int = NativeApi.SUCCESS_RESULT

		override fun reflexInputSample(): Int {
			reflexCalls += "inputSample"
			return if (failReflex) NativeApi.SUCCESS_RESULT + 1 else NativeApi.SUCCESS_RESULT
		}

		override fun reflexMarker(type: NativeApi.ReflexMarkerType): Int {
			reflexCalls += when (type) {
				NativeApi.ReflexMarkerType.SIMULATION_START -> "simulateStart"
				NativeApi.ReflexMarkerType.SIMULATION_END -> "simulateEnd"
				NativeApi.ReflexMarkerType.RENDER_SUBMIT_START -> "renderSubmitStart"
				NativeApi.ReflexMarkerType.RENDER_SUBMIT_END -> "renderSubmitEnd"
				NativeApi.ReflexMarkerType.INPUT_SAMPLE -> return FAIL_INVALID_PARAMETER
			}
			return NativeApi.SUCCESS_RESULT
		}
	}

	private class HeadlessRenderTarget(width: Int, height: Int) : RenderTarget("fake", true, GpuFormat.RGBA8_UNORM) {
		init {
			this.width = width
			this.height = height
		}

		override fun createBuffers(width: Int, height: Int) {
			this.width = width
			this.height = height
		}

		override fun destroyBuffers() = Unit
	}

	private companion object {
		/** NVSDK_NGX_Result_FAIL_NotInitialized = NVSDK_NGX_Result_Fail | 7 (0xBAD00000 | 7). */
		const val FAIL_NOT_INITIALIZED = 0xBAD00007.toInt()

		/** NVSDK_NGX_Result_FAIL_InvalidParameter = NVSDK_NGX_Result_Fail | 5 (0xBAD00000 | 5). */
		const val FAIL_INVALID_PARAMETER = 0xBAD00005.toInt()

		/** sl::ReflexMode::eLowLatency, the mode the READY registration records. */
		const val LOW_LATENCY = 1
	}
}
