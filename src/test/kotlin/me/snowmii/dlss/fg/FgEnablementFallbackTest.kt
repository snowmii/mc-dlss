package me.snowmii.dlss.fg

import java.nio.file.Path
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.bridge.DlssEvaluationImages
import me.snowmii.dlss.bridge.DlssFrameTimings
import me.snowmii.dlss.bridge.EvaluationRequest
import me.snowmii.dlss.bridge.ExtensionBootstrap
import me.snowmii.dlss.bridge.FgState
import me.snowmii.dlss.bridge.FgTagRequest
import me.snowmii.dlss.bridge.FillVelocityRequest
import me.snowmii.dlss.bridge.HeadlessVulkanFixture
import me.snowmii.dlss.bridge.ImageBinding
import me.snowmii.dlss.bridge.MotionRequest
import me.snowmii.dlss.bridge.Native
import me.snowmii.dlss.bridge.NativeApi
import me.snowmii.dlss.bridge.PresentTarget
import me.snowmii.dlss.bridge.SrTagRequest
import me.snowmii.dlss.bridge.VulkanContext
import me.snowmii.dlss.client.RuntimeControls
import me.snowmii.dlss.fg.FgSurfacePolicy
import me.snowmii.dlss.mrt.MotionVectorRoute
import me.snowmii.dlss.render.DlssCameraSample
import me.snowmii.dlss.render.DlssFrameMotion
import me.snowmii.dlss.render.DlssJitter
import me.snowmii.dlss.render.DlssJitterOffset
import me.snowmii.dlss.render.FgFrameInputs
import me.snowmii.dlss.render.FrameEvaluation
import me.snowmii.dlss.render.RenderRuntime
import me.snowmii.dlss.render.SceneResources
import me.snowmii.dlss.render.SceneTarget
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssSessionState
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.session.SRMode
import me.snowmii.dlss.sl.SrLiveSession
import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.lwjgl.vulkan.VkCommandBuffer

/**
 * M-13 rung: status-driven FG-off session latch, the user-toggle disable path, and the
 * frame-support suspension.
 *
 * While FG is active, any status other than eDLSSGStatusOk (word zero) in the live
 * `slDLSSGGetState` read latches FG off for the session: the surface policy turns the mode
 * off (vsync and image-count reads restored, every re-arm refused), the DLSS-G options are
 * re-recorded in the eOff mode with retained resources, the SR session stays READY - the
 * fallback is SR-only, not vanilla - and one exact diagnostic names the status word.
 *
 * The user toggle rides the same seams with the opposite re-arm rule: switching FG off
 * through the controls restores the policy reads and records the retained eOff options
 * exactly once on the transition, leaves SR READY and the split active, and stays
 * re-armable - while a status-latched policy keeps refusing the re-arm without a second
 * diagnostic.
 *
 * Frame support suspends the effective mode through the same retained eOff record without
 * touching the user's mode or the latch: FG composes only on supported in-world frames, an
 * unsupported frame (pause, loading, menu, panorama, resize, fullscreen transition)
 * suspends exactly once per transition, the frames in between compose SR-only, a supported
 * frame resumes, and a user-off or latched policy stays off through the whole cycle.
 *
 * A level-change discontinuity resets the shared history: the first supported composed frame
 * afterwards records resetHistory through the shared evaluation seam and the next frame
 * accumulates normally, while FG stays on and suspension, user-off, and session-latch
 * precedence stay intact.
 *
 * The M-11 and M-12 rungs already proved the native seams live (present handoff, input
 * wait, markers); this rung proves the latch wiring off the render thread through the
 * production seams: the runtime's per-frame poll, the policy's latch and re-arm refusal,
 * the adapter's non-latching eOff record, and the SR-only frame that follows - plus the
 * native mode record's live gates and eOff acceptance against the real Streamline session,
 * the same live proof shape as the M-9 options rung.
 */
class FgEnablementFallbackTest {

	@Test
	fun `the unsupported frame auto suspends FG and a supported frame resumes it`() {
		val calls = RecordingNative()
		val policy = FgSurfacePolicy()
		var supported = true
		val harness = harness(calls, policy, fgFrameSupported = { _, _ -> supported })
		val announced = mutableListOf<String>()
		val controls = RuntimeControls(harness.runtime, announced::add)

		// Arm FG through the controls and prove one supported frame composes it.
		controls.toggleFrameGeneration()
		assertTrue(policy.active, "the supported session starts with FG effective")
		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION, MotionVectorRoute.CAMERA_ONLY),
			"a supported frame must compose FG",
		)
		assertEquals(1, calls.fgConfigures.size, "the supported frame records its FG options once")

		// The first unsupported frame suspends the effective mode and records the retained
		// eOff options exactly once - the SR route and the user's mode stay untouched.
		supported = false
		assertNotNull(
			harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS),
			"the suspended frame still routes to the scene target: SR stays on",
		)
		harness.runtime.endWorldPhase()
		assertFalse(policy.active, "an unsupported frame suspends effective FG")
		assertEquals(1, calls.fgModeOffRecords, "the suspension records the retained eOff mode exactly once")
		assertEquals(0, harness.probe.diagnostics.size, "a suspension is not a latch: no diagnostic")

		// More unsupported frames record nothing, and a suspended frame composes SR-only.
		harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS)
		harness.runtime.endWorldPhase()
		assertEquals(1, calls.fgModeOffRecords, "a suspension already in effect records nothing")
		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION, MotionVectorRoute.CAMERA_ONLY),
			"an SR-only frame during the suspension must record and present",
		)
		assertEquals(1, calls.fgConfigures.size, "no FG options may record while suspended")
		assertEquals(2, calls.fgTags.size, "no FG tags may record while suspended")
		assertEquals(1, calls.handoffs, "no present handoff may record while suspended")

		// The supported frame resumes: no mode record, and the next frame composes FG again.
		supported = true
		harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS)
		harness.runtime.endWorldPhase()
		assertTrue(policy.active, "a supported frame resumes effective FG")
		assertEquals(1, calls.fgModeOffRecords, "the resume records no mode")
		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION, MotionVectorRoute.CAMERA_ONLY),
			"the resumed frame must compose FG again",
		)
		assertEquals(2, calls.fgConfigures.size, "the resumed frame re-records the eOn options per frame")
		assertTrue(announced.last().contains("fg on"), "the readout reports the resumed mode: ${announced.last()}")
	}

	@Test
	fun `the frame after a level change discontinuity composes FG with the shared reset flag recorded`() {
		val calls = RecordingNative()
		val policy = FgSurfacePolicy()
		val harness = harness(calls, policy)
		val announced = mutableListOf<String>()
		val controls = RuntimeControls(harness.runtime, announced::add)

		// Arm FG through the controls and compose one supported frame, so the history starts
		// fresh and the next one accumulates against it.
		controls.toggleFrameGeneration()
		assertTrue(policy.active, "the session starts with FG active")
		composeSupportedFrame(harness, stillCamera())
		assertTrue(calls.evaluations[0].resetHistory, "the first frame of a session restarts history")

		composeSupportedFrame(harness, stillCamera())
		assertFalse(calls.evaluations[1].resetHistory, "the second frame accumulates against the first")

		// The level-change discontinuity: the scene is replaced, so the history the new world
		// would accumulate is worthless. Production reaches this seam from the client thread
		// through WorldPhase.resetHistory, which delegates here.
		harness.runtime.resetHistory()

		// The first supported composed frame after the change records the reset flag through
		// the shared evaluation seam, and still composes FG.
		composeSupportedFrame(harness, stillCamera())
		assertTrue(
			calls.evaluations[2].resetHistory,
			"the first composed frame after the discontinuity must record resetHistory",
		)
		assertEquals(3, calls.fgConfigures.size, "the reset frame still composes FG: no frame is classified unsupported")

		// The next frame accumulates normally against the reset frame.
		composeSupportedFrame(harness, stillCamera())
		assertFalse(calls.evaluations[3].resetHistory, "the frame after the reset accumulates normally")
		assertEquals(4, calls.fgConfigures.size, "every frame here composes FG")

		// The discontinuity leaves every other precedence intact: FG stays on, nothing suspends
		// or latches, nothing re-records the eOff mode, and the SR session stays READY.
		assertTrue(policy.active, "a discontinuity is not a suspension: FG stays on")
		assertFalse(policy.latched, "a discontinuity is not a latch")
		assertEquals(0, calls.fgModeOffRecords, "a discontinuity records no eOff mode")
		assertEquals(0, harness.probe.diagnostics.size, "a discontinuity emits no diagnostic")
		assertEquals(DlssSessionState.READY, harness.session.state, "the SR session stays READY")
		assertTrue(announced.last().contains("fg on"), "the readout still reports FG on: ${announced.last()}")
	}

	/** Runs one supported world phase and evaluates it against the phase's published values. */
	private fun composeSupportedFrame(harness: Harness, camera: DlssCameraSample) {
		assertNotNull(
			harness.runtime.beginWorldPhase(
				normalInWorldFrame = true,
				outputDimensions = OUTPUT_DIMENSIONS,
				camera = camera,
			),
			"the supported frame routes to the scene target",
		)
		val jitter = harness.runtime.activeJitter
		val motion = harness.runtime.activeMotion
		assertNotNull(jitter, "the supported frame publishes jitter")
		assertNotNull(motion, "the supported frame publishes camera motion")
		assertTrue(
			harness.evaluation.evaluateFrame(
				scene(),
				jitter!!,
				motion!!,
				DESTINATION,
				MotionVectorRoute.CAMERA_ONLY,
				camera = camera,
			),
			"the supported frame must compose",
		)
		harness.runtime.endWorldPhase(completedDlssFrame = true)
	}

	/** A camera standing still, so consecutive frames are continuous and no reset is fabricated. */
	private fun stillCamera() = DlssCameraSample(
		projection = Matrix4f(),
		viewRotation = Matrix4f(),
		cameraX = 0.0,
		cameraY = 100.0,
		cameraZ = 0.0,
	)

	@Test
	fun `unsupported frames never overturn a user-off policy or the session latch`() {
		// User-off precedence: with FG off, a suspension cycle records no mode and a resume
		// does not re-arm.
		val calls = RecordingNative()
		val policy = FgSurfacePolicy()
		var supported = true
		val harness = harness(calls, policy, fgFrameSupported = { _, _ -> supported })

		supported = false
		harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS)
		harness.runtime.endWorldPhase()
		supported = true
		harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS)
		harness.runtime.endWorldPhase()
		assertFalse(policy.active, "a user-off policy stays off through suspend and resume")
		assertEquals(0, calls.fgModeOffRecords, "a suspended user-off policy records no mode")

		// Latch precedence: the latch owns the one eOff record and diagnostic; a following
		// suspension and resume change nothing and never re-arm.
		val latchedCalls = RecordingNative()
		val latchedPolicy = FgSurfacePolicy()
		var latchedSupported = true
		val latchedHarness = harness(latchedCalls, latchedPolicy, fgFrameSupported = { _, _ -> latchedSupported })
		assertTrue(latchedPolicy.setFrameGenerationActive(true), "FG must arm first")
		latchedCalls.status = FgState(2, 1, 0L, 0L)
		latchedHarness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS)
		latchedHarness.runtime.endWorldPhase()
		assertTrue(latchedPolicy.latched, "the bad status must latch FG off")
		assertEquals(1, latchedCalls.fgModeOffRecords, "the latch records the eOff mode")

		latchedSupported = false
		latchedHarness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS)
		latchedHarness.runtime.endWorldPhase()
		latchedSupported = true
		latchedHarness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS)
		latchedHarness.runtime.endWorldPhase()

		assertFalse(latchedPolicy.active, "a latched policy stays off through suspend and resume")
		assertTrue(latchedPolicy.latched, "the resume must not overturn the latch")
		assertEquals(1, latchedCalls.fgModeOffRecords, "the latch keeps the one eOff record")
		assertEquals(1, latchedHarness.probe.diagnostics.size, "the latch keeps its one diagnostic")
	}

	@Test
	fun `the FG frame-support classifier names every unsupported frame kind`() {
		assertTrue(
			RenderRuntime.isFgFrameSupported(
				normalInWorldFrame = true,
				outputMatches = true,
				paused = false,
				loading = false,
				screenOpen = false,
				fullscreenTransition = false,
			),
			"a plain in-world frame at the configured size is the only supported kind",
		)
		assertFalse(
			RenderRuntime.isFgFrameSupported(
				normalInWorldFrame = false,
				outputMatches = true,
				paused = false,
				loading = false,
				screenOpen = false,
				fullscreenTransition = false,
			),
			"a panorama frame is unsupported",
		)
		assertFalse(
			RenderRuntime.isFgFrameSupported(
				normalInWorldFrame = true,
				outputMatches = false,
				paused = false,
				loading = false,
				screenOpen = false,
				fullscreenTransition = false,
			),
			"a resize or resolution-change frame is unsupported",
		)
		assertFalse(
			RenderRuntime.isFgFrameSupported(
				normalInWorldFrame = true,
				outputMatches = true,
				paused = true,
				loading = false,
				screenOpen = false,
				fullscreenTransition = false,
			),
			"a paused frame is unsupported",
		)
		assertFalse(
			RenderRuntime.isFgFrameSupported(
				normalInWorldFrame = true,
				outputMatches = true,
				paused = false,
				loading = true,
				screenOpen = false,
				fullscreenTransition = false,
			),
			"a loading frame is unsupported",
		)
		assertFalse(
			RenderRuntime.isFgFrameSupported(
				normalInWorldFrame = true,
				outputMatches = true,
				paused = false,
				loading = false,
				screenOpen = true,
				fullscreenTransition = false,
			),
			"a menu frame is unsupported",
		)
		assertFalse(
			RenderRuntime.isFgFrameSupported(
				normalInWorldFrame = true,
				outputMatches = true,
				paused = false,
				loading = false,
				screenOpen = false,
				fullscreenTransition = true,
			),
			"a fullscreen-transition frame is unsupported",
		)
	}

	@Test
	fun `a non-OK status while FG is active latches FG off once with one exact diagnostic and keeps SR READY`() {
		val calls = RecordingNative()
		val policy = FgSurfacePolicy()
		val harness = harness(calls, policy)

		assertTrue(
			policy.setFrameGenerationActive(true),
			"the frame starts with FG active",
		)
		calls.status = FgState(0, 2, 0L, 0L)

		// Frame 1: eDLSSGStatusOk keeps FG active and emits nothing.
		assertNotNull(
			harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS),
			"an OK-status FG frame must route to the scene target",
		)
		harness.runtime.endWorldPhase()
		assertTrue(policy.active, "an OK status must not latch FG off")
		assertEquals(0, harness.probe.diagnostics.size, "an OK status must emit no diagnostic")
		assertEquals(0, calls.fgModeOffRecords, "an OK status must record no eOff options")

		// Frame 2: status 0x2 (eFailReflexNotDetectedAtRuntime) while FG is active latches
		// FG off for the session, re-records the eOff options once, and emits exactly one
		// diagnostic naming the status word.
		calls.status = FgState(2, 1, 0L, 0L)
		assertNotNull(
			harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS),
			"the latched frame must still route to the scene target: SR stays READY",
		)
		harness.runtime.endWorldPhase()

		assertFalse(policy.active, "a non-OK status must switch FG off")
		assertTrue(policy.latched, "the latch must be recorded for the session")
		assertEquals(1, calls.fgModeOffRecords, "the latch must re-record the DLSS-G options in the eOff mode exactly once")
		assertEquals(
			listOf(
				"Frame generation latched off: slDLSSGGetState status=0x2 (eDLSSGStatusOk=0); " +
					"eOff options retained, vsync restored, SR session stays READY, re-arm refused.",
			),
			harness.probe.diagnostics,
			"the latch must emit exactly one diagnostic naming the status word",
		)
		assertEquals(
			DlssSessionState.READY,
			harness.session.state,
			"the status latch must not touch the SR session: SR stays READY",
		)

		// Frame 3: the poll no longer runs (FG is off), and a further bad status must not
		// re-emit the diagnostic or re-record the eOff options.
		calls.status = FgState(2, 1, 0L, 0L)
		assertNotNull(
			harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS),
			"an SR-only frame after the latch must still route to the scene target",
		)
		harness.runtime.endWorldPhase()
		assertEquals(1, calls.fgModeOffRecords, "the eOff record must stay attached to the first latch")
		assertEquals(1, harness.probe.diagnostics.size, "the diagnostic must be emitted exactly once per session")
	}

	@Test
	fun `a latched policy refuses re-arm and restores vsync and image count`() {
		var invalidations = 0
		val policy = FgSurfacePolicy(invalidateSurfaceConfiguration = { invalidations++ })

		assertTrue(policy.setFrameGenerationActive(true), "FG must arm first")
		assertFalse(policy.effectiveVsyncEnabled(true), "while FG is active the reconfigure path must read vsync false")
		assertEquals(3, policy.minImageCount(2), "while FG is active the swapchain count must cover the declared back buffers")

		assertTrue(policy.latchOff(), "the first latch is the one that latches")
		assertFalse(policy.active, "the latch must switch FG off")
		assertTrue(policy.latched, "the latch must be recorded")
		assertEquals(2, invalidations, "arming and latching each invalidate the surface configuration exactly once")

		// Restored policy reads: the stored vsync value untouched and Minecraft's own image
		// count, exactly as before FG ever armed.
		assertTrue(policy.effectiveVsyncEnabled(true), "after the latch vsync must read the stored option again")
		assertEquals(2, policy.minImageCount(2), "after the latch the image count must be Minecraft's own")

		// Re-arm is refused: the toggle may not overturn the plugin's own failure verdict,
		// and the refusal must not invalidate anything.
		assertFalse(policy.setFrameGenerationActive(true), "a latched policy must refuse re-arm")
		assertFalse(policy.active, "the refused re-arm must leave FG off")
		assertEquals(2, invalidations, "a refused re-arm must not invalidate the surface configuration")

		// A repeat latch is a no-op, so a caller can keep one-shot side effects (the eOff
		// record, the diagnostic) attached to the first latch.
		assertFalse(policy.latchOff(), "a repeat latch must answer false")

		// A policy that latches before it ever armed still latches, and invalidates nothing
		// because the mode never changed.
		var offArmInvalidations = 0
		val neverArmed = FgSurfacePolicy(invalidateSurfaceConfiguration = { offArmInvalidations++ })
		assertTrue(neverArmed.latchOff(), "a never-armed policy can still latch")
		assertTrue(neverArmed.latched)
		assertFalse(neverArmed.active)
		assertEquals(0, offArmInvalidations, "a latch that changes no mode must invalidate nothing")
	}

	@Test
	fun `after the latch the frame records SR only with no FG calls and the session stays READY`() {
		val calls = RecordingNative()
		val policy = FgSurfacePolicy()
		val harness = harness(calls, policy)

		assertTrue(policy.setFrameGenerationActive(true))
		calls.status = FgState(2, 1, 0L, 0L)
		harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS)
		harness.runtime.endWorldPhase()
		assertTrue(policy.latched, "the frame must latch before its recording")

		// The frame that follows the latch records the SR-only composition: motion, SR tag,
		// evaluation, present - no FG options, no FG tags, no handoff - and still succeeds,
		// which is the subsequent SR evaluation the fallback exists to keep.
		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION, MotionVectorRoute.CAMERA_ONLY),
			"an SR-only frame after the latch must record and present",
		)
		assertEquals(
			listOf("writeMotion", "srTag", "evaluate", "present"),
			calls.order.filter { it != "waitFgInputs" && it != "setFgModeOff" },
			"after the latch the frame must record the SR composition with no FG calls at all",
		)
		assertEquals(0, calls.fgConfigures.size, "after the latch no FG options may record")
		assertEquals(0, calls.fgTags.size, "after the latch no FG tags may record")
		assertEquals(0, calls.handoffs, "after the latch no present handoff may record")
		assertEquals(
			DlssSessionState.READY,
			harness.session.state,
			"the SR session must stay READY through the latched frame",
		)
	}

	@Test
	fun `the user toggle records the retained eOff options exactly once and re-arms with the SR session untouched`() {
		val calls = RecordingNative()
		val policy = FgSurfacePolicy()
		val harness = harness(calls, policy)
		val announced = mutableListOf<String>()
		val controls = RuntimeControls(harness.runtime, announced::add)

		// Arm through the controls: the reviewer's key press is the public boundary.
		controls.toggleFrameGeneration()
		assertTrue(policy.active, "the controls switch FG on")
		assertFalse(policy.latched, "arming never latches")
		assertEquals(0, calls.fgModeOffRecords, "arming records no mode")
		assertTrue(announced.last().contains("fg on"), "the readout names the mode in effect: ${announced.last()}")

		// One FG frame composes with the policy active and an OK status.
		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION, MotionVectorRoute.CAMERA_ONLY),
			"an FG frame must record and present",
		)
		assertEquals(1, calls.fgConfigures.size, "the FG frame records its options once")

		// The user toggle off: the policy restores its reads, one retained eOff record runs,
		// and nothing about the SR session changes.
		controls.toggleFrameGeneration()
		assertFalse(policy.active, "the controls switch FG off")
		assertFalse(policy.latched, "a user toggle is not a latch")
		assertEquals(1, calls.fgModeOffRecords, "the off transition records the eOff mode exactly once")
		assertEquals(listOf(0), calls.fgModeValues, "the eOff record passes mode zero to the bridge")
		assertTrue(policy.effectiveVsyncEnabled(true), "the reconfigure read is the stored vsync again")
		assertEquals(2, policy.minImageCount(2), "the swapchain count is Minecraft's own again")
		assertEquals(
			DlssSessionState.READY,
			harness.session.state,
			"the user toggle must not touch the SR session: SR stays READY",
		)
		assertTrue(announced.last().contains("fg off"), "the readout names the mode in effect: ${announced.last()}")

		// The SR-only frame after the toggle records no FG calls, still presents, and does
		// not repeat the eOff record.
		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION, MotionVectorRoute.CAMERA_ONLY),
			"an SR-only frame after the user toggle must record and present",
		)
		assertEquals(1, calls.fgConfigures.size, "no FG options may record after the toggle")
		assertEquals(2, calls.fgTags.size, "no FG tags may record after the toggle")
		assertEquals(1, calls.handoffs, "no present handoff may record after the toggle")
		assertEquals(1, calls.fgModeOffRecords, "the eOff record must stay attached to the one transition")

		// Re-arm through the controls: a user-off policy is re-armable, and the first FG
		// frame re-records the eOn options through the per-frame options record.
		controls.toggleFrameGeneration()
		assertTrue(policy.active, "a user-off policy re-arms")
		assertFalse(policy.latched)
		assertEquals(1, calls.fgModeOffRecords, "re-arming records no mode")
		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION, MotionVectorRoute.CAMERA_ONLY),
			"the re-armed frame must compose FG again",
		)
		assertEquals(2, calls.fgConfigures.size, "the re-armed frame re-records the eOn options")

		// And off again records once more: exactly once per off transition, never per frame.
		controls.toggleFrameGeneration()
		assertFalse(policy.active)
		assertEquals(2, calls.fgModeOffRecords, "each off transition records exactly once")
		assertEquals(listOf(0, 0), calls.fgModeValues, "each eOff record passes mode zero")
		assertEquals(
			DlssSessionState.READY,
			harness.session.state,
			"the SR session stays READY through both user cycles",
		)
	}

	@Test
	fun `a status-latched policy refuses the user re-arm without a second diagnostic or record`() {
		val calls = RecordingNative()
		val policy = FgSurfacePolicy()
		val harness = harness(calls, policy)
		val announced = mutableListOf<String>()
		val controls = RuntimeControls(harness.runtime, announced::add)

		controls.toggleFrameGeneration()
		assertTrue(policy.active, "the session starts with FG active")
		calls.status = FgState(2, 1, 0L, 0L)
		assertNotNull(
			harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS),
			"the latched frame must still route to the scene target: SR stays READY",
		)
		harness.runtime.endWorldPhase()
		assertTrue(policy.latched, "the bad status must latch")
		assertEquals(1, calls.fgModeOffRecords, "the latch records the eOff mode")
		assertEquals(1, harness.probe.diagnostics.size, "the latch emits its one exact diagnostic")

		// The user re-arm is refused: the plugin's own failure verdict may not be overturned,
		// and the refusal records nothing and emits no new diagnostic.
		controls.toggleFrameGeneration()
		assertFalse(policy.active, "the re-arm after the latch must stay refused")
		assertTrue(policy.latched)
		assertEquals(1, calls.fgModeOffRecords, "the refused re-arm records no mode")
		assertEquals(
			listOf(
				"Frame generation latched off: slDLSSGGetState status=0x2 (eDLSSGStatusOk=0); " +
					"eOff options retained, vsync restored, SR session stays READY, re-arm refused.",
			),
			harness.probe.diagnostics,
			"the latch's one exact diagnostic must remain the only one",
		)
		assertTrue(announced.last().contains("fg off"), "the readout reports the state actually in effect: ${announced.last()}")

		// A second attempt changes nothing either.
		controls.toggleFrameGeneration()
		assertFalse(policy.active, "the re-arm must stay refused on every attempt")
		assertEquals(1, harness.probe.diagnostics.size, "no attempt may emit a new diagnostic")
		assertEquals(1, calls.fgModeOffRecords, "no attempt may record a new mode")
	}

	@Test
	fun `the adapter records the eOff mode without latching the session`() {
		val native = FakeNative()
		val session = DlssSession(
			DlssStartupConfig(
				enabled = true,
				qualityMode = SRMode.QUALITY,
				outputDimensions = OUTPUT_DIMENSIONS,
				sdkPath = null,
				nativeLibraryPath = null,
				dataPath = null,
				warnings = emptyList(),
			),
		)
		val adapter = LifecycleAdapter(session, native)

		// Not ready yet: the eOff record must not reach the bridge.
		assertFalse(adapter.recordFgModeOff(), "a session that is not READY must not record the eOff mode")
		assertEquals(0, native.setFgModeValues.size)

		assertTrue(adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")) != null)
		assertTrue(adapter.recordFgModeOff(), "a READY session must record the eOff mode")
		assertEquals(listOf(0), native.setFgModeValues, "the eOff record must pass mode zero to the bridge")

		// A refused eOff record is invisible to the session: the status latch leaves SR READY
		// by design, so the record failure must not send the session to FALLBACK_LATCHED.
		native.setFgModeResult = NativeApi.SUCCESS_RESULT + 1
		assertFalse(adapter.recordFgModeOff(), "a refused eOff record must answer false")
		assertEquals(DlssSessionState.READY, session.state, "the refused eOff record must not latch the SR session")
	}

	@Test
	fun `the FG mode record answers the live gates and records eOff after configured options`(
		@TempDir dataPath: Path,
	) {
		// Pre-init: this fork's module has never bootstrapped, so the mode record has no
		// Streamline session to answer through. The check runs before the live session below
		// and on a throwaway bridge, and the module's bootstrap state is what it asserts
		// against.
		Native.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
			assertEquals(
				FAIL_NOT_INITIALIZED,
				bridge.setFgMode(0),
				"setFgMode before bootstrap must answer FAIL_NotInitialized",
			)
		}

		val instanceExtensions = ExtensionBootstrap.queryInstanceExtensions()
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

				// The mode record switches an existing options record, so a session whose
				// options never recorded has nothing to switch: the same gate as the FG tag.
				assertEquals(
					FAIL_INVALID_PARAMETER,
					bridge.setFgMode(0),
					"setFgMode before any FG options record must answer FAIL_InvalidParameter",
				)

				val outputWidth = 2560
				val outputHeight = 1440
				val dimensions = bridge.queryOptimalDimensions(outputWidth, outputHeight, 2)
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
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.configureFg(3),
					"the eOn options must record before the mode record can switch them",
				)

				// The eOff record: same stored record, mode switched, retained resources.
				// slDLSSGSetOptions answers eOk only when it accepts the record, so the
				// success result is the seam's observable contract, exactly as for the eOn
				// record of the M-9 rung.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.setFgMode(0),
					"after a ready session and a stored options record the eOff mode must record",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.setFgMode(1),
					"the mode record must also answer the eOn mode",
				)

				// Arm the close path: the already-activated tuple is recorded through the
				// existing initialize, so this bridge's close runs the orderly slShutdown while
				// the device is still alive instead of leaving the fork to crash at exit.
				SrLiveSession.recordActivatedSession(bridge, fixture, dataPath)
			}
		}
	}

	/** Builds the production seams over a recording fake and a READY session. */
	private fun harness(
		calls: RecordingNative,
		policy: FgSurfacePolicy,
		fgFrameSupported: (Boolean, DlssDimensions) -> Boolean = { normalInWorldFrame, outputDimensions ->
			normalInWorldFrame && outputDimensions == OUTPUT_DIMENSIONS
		},
	): Harness {
		val session = DlssSession(
			DlssStartupConfig(
				enabled = true,
				qualityMode = SRMode.QUALITY,
				outputDimensions = OUTPUT_DIMENSIONS,
				sdkPath = null,
				nativeLibraryPath = null,
				dataPath = null,
				warnings = emptyList(),
			),
		)
		val adapter = LifecycleAdapter(session, calls)
		adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data"))
		val counters = Counters()
		val context = VulkanContext.fromNativeHandles(
			1L,
			2L,
			3L,
			4L,
			commandBufferSource = {
				counters.buffers++
				fakeCommandBuffer()
			},
			commandBufferSink = { counters.submits++ },
		)
		val evaluation = FrameEvaluation(
			adapter,
			{ context },
			frameGeneration = policy,
			fgInputs = { fgInputs() },
		)
		val probe = LatchProbe()
		val runtime = RenderRuntime(
			session = session,
			sceneTarget = SceneTarget(
				allocate = { width, height -> FakeTarget(width, height) },
				release = {},
			),
			startup = { RENDER_DIMENSIONS },
			frameEvaluation = evaluation,
			frameGeneration = policy,
			waitForFgInputs = { adapter.waitFgInputsIdle() },
			// Production wiring shape: the poll reads through the adapter, the off-transition
			// eOff record (status latch, user toggle, and frame-support suspension alike) goes
			// through it too, and the latch's one exact diagnostic is emitted through the
			// diagnostics seam.
			pollFgState = { adapter.queryFgState() },
			recordFgModeOff = { adapter.recordFgModeOff() },
			diagnostics = { probe.diagnostics += it },
			fgFrameSupported = fgFrameSupported,
		)
		return Harness(runtime, session, evaluation, probe)
	}

	/** A [VkCommandBuffer] instance whose address() answers without any Vulkan device. */
	private fun fakeCommandBuffer(): VkCommandBuffer {
		val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
		unsafeField.isAccessible = true
		val unsafe = unsafeField.get(null) as sun.misc.Unsafe
		return unsafe.allocateInstance(VkCommandBuffer::class.java) as VkCommandBuffer
	}

	private fun scene() = SceneResources(
		color = ImageBinding(201L, 202L, 37),
		depth = ImageBinding(301L, 302L, 126),
	)

	private fun fgInputs() = FgFrameInputs(
		hudless = ImageBinding(601L, 602L, 37),
		ui = ImageBinding(701L, 702L, 37),
	)

	private fun jitter(): DlssJitterOffset = DlssJitter(RENDER_DIMENSIONS, OUTPUT_DIMENSIONS).advance()

	private fun motion() =
		DlssFrameMotion(Matrix4f(), RENDER_DIMENSIONS.width / 2f, RENDER_DIMENSIONS.height / 2f, 16.6f, false)

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

	/** The family the fixture creates its queues in, discovered with a throwaway default fixture. */
	private fun probeGraphicsQueueFamily(): Int =
		HeadlessVulkanFixture().use { it.graphicsQueueFamilyIndex() }

	private class Harness(
		val runtime: RenderRuntime,
		val session: DlssSession,
		val evaluation: FrameEvaluation,
		val probe: LatchProbe,
	)

	private class LatchProbe {
		val diagnostics = mutableListOf<String>()
	}

	private class Counters {
		var buffers = 0
		var submits = 0
	}

	/**
	 * Answers the live status the poll reads and records every per-frame native call in
	 * submission order; everything else is the lifecycle [LifecycleAdapter] drives to READY.
	 */
	private class RecordingNative : NativeApi {
		var status: FgState = FgState(0, 1, 0L, 0L)
		val order = mutableListOf<String>()
		val fgTags = mutableListOf<FgTagRequest>()
		val fgConfigures = mutableListOf<Int>()
		var fgModeOffRecords = 0
		val fgModeValues = mutableListOf<Int>()
		var handoffs = 0
		val evaluations = mutableListOf<EvaluationRequest>()

		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		): Int = NativeApi.SUCCESS_RESULT

		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int): DlssDimensions =
			RENDER_DIMENSIONS

		override fun configure(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		): Int = NativeApi.SUCCESS_RESULT

		override fun acquireImages(): DlssEvaluationImages = DlssEvaluationImages(
			motion = ImageBinding(401L, 402L, 124),
			output = ImageBinding(501L, 502L, 37),
		)

		override fun releaseImages(): Int = NativeApi.SUCCESS_RESULT

		override fun waitDeviceIdle(): Int = NativeApi.SUCCESS_RESULT

		override fun frameTimings(): DlssFrameTimings? = null

		override fun waitFgInputsIdle(): Int {
			order += "waitFgInputs"
			return NativeApi.SUCCESS_RESULT
		}

		override fun queryFgState(): FgState = status

		override fun setFgMode(fgEnabled: Int): Int {
			fgModeValues += fgEnabled
			fgModeOffRecords++
			order += "setFgModeOff"
			return NativeApi.SUCCESS_RESULT
		}

		override fun configureFg(numBackBuffers: Int): Int {
			fgConfigures += numBackBuffers
			order += "configureFg"
			return NativeApi.SUCCESS_RESULT
		}

		override fun tagFgResources(request: FgTagRequest): Int {
			fgTags += request
			order += "fgTag"
			return NativeApi.SUCCESS_RESULT
		}

		override fun presentHandoff(): Int {
			handoffs++
			order += "handoff"
			return NativeApi.SUCCESS_RESULT
		}

		override fun tagSrResources(request: SrTagRequest): Int {
			order += "srTag"
			return NativeApi.SUCCESS_RESULT
		}

		override fun writeMotion(request: MotionRequest): Int {
			order += "writeMotion"
			return NativeApi.SUCCESS_RESULT
		}

		override fun fillVelocity(request: FillVelocityRequest): Int {
			order += "fillVelocity"
			return NativeApi.SUCCESS_RESULT
		}

		override fun presentOutput(target: PresentTarget): Int {
			order += "present"
			return NativeApi.SUCCESS_RESULT
		}

		override fun evaluate(request: EvaluationRequest): Int {
			evaluations += request
			order += "evaluate"
			return NativeApi.SUCCESS_RESULT
		}
	}

	/** Records the eOff-mode seam and answers the three calls [LifecycleAdapter.initialize] drives. */
	private class FakeNative : NativeApi {
		var setFgModeResult = NativeApi.SUCCESS_RESULT
		val setFgModeValues = mutableListOf<Int>()

		override fun setFgMode(fgEnabled: Int): Int {
			setFgModeValues += fgEnabled
			return setFgModeResult
		}

		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		): Int = NativeApi.SUCCESS_RESULT

		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int) =
			DlssDimensions(1280, 720)

		override fun configure(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		): Int = NativeApi.SUCCESS_RESULT

		override fun acquireImages(): DlssEvaluationImages = error("unexpected acquireImages")
		override fun releaseImages(): Int = error("unexpected releaseImages")
		override fun waitDeviceIdle(): Int = error("unexpected waitDeviceIdle")
		override fun frameTimings(): DlssFrameTimings? = error("unexpected frameTimings")
		override fun writeMotion(request: MotionRequest): Int = error("unexpected writeMotion")
		override fun presentOutput(target: PresentTarget): Int = error("unexpected presentOutput")
		override fun evaluate(request: EvaluationRequest): Int = error("unexpected evaluate")
	}

	/** Render target with no GPU buffers, so the runtime is testable off the render thread. */
	private class FakeTarget(width: Int, height: Int) : RenderTarget("fake", true, GpuFormat.RGBA8_UNORM) {
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
		val RENDER_DIMENSIONS = DlssDimensions(1280, 720)
		val OUTPUT_DIMENSIONS = DlssDimensions(2560, 1440)
		const val DESTINATION = 999L

		/** NVSDK_NGX_Result_FAIL_NotInitialized = NVSDK_NGX_Result_Fail | 7 (0xBAD00000 | 7). */
		private val FAIL_NOT_INITIALIZED = 0xBAD00007.toInt()

		/** NVSDK_NGX_Result_FAIL_InvalidParameter = NVSDK_NGX_Result_Fail | 5 (0xBAD00000 | 5). */
		private val FAIL_INVALID_PARAMETER = 0xBAD00005.toInt()
	}
}
