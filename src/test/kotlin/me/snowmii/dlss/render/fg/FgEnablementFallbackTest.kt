package me.snowmii.dlss.render.fg

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import me.snowmii.dlss.client.RuntimeControls
import me.snowmii.dlss.render.DlssCameraSample
import me.snowmii.dlss.render.DlssFrameMotion
import me.snowmii.dlss.render.DlssJitter
import me.snowmii.dlss.render.DlssJitterOffset
import me.snowmii.dlss.render.FgFrameInputs
import me.snowmii.dlss.render.FgSurfacePolicy
import me.snowmii.dlss.render.FrameEvaluation
import me.snowmii.dlss.render.RenderRuntime
import me.snowmii.dlss.render.SceneResources
import me.snowmii.dlss.render.SceneTarget
import me.snowmii.dlss.DlssSession
import me.snowmii.dlss.DlssSessionState
import me.snowmii.dlss.DlssStartupConfig
import me.snowmii.dlss.streamline.LifecycleAdapter
import me.snowmii.dlss.SRMode
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.EvaluationImages
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.streamline.FgState
import me.snowmii.streamline.FgTagRequest
import me.snowmii.streamline.FrameTimings
import me.snowmii.dlss.api.ImageBinding
import me.snowmii.streamline.MotionRequest
import me.snowmii.streamline.PresentTarget
import me.snowmii.streamline.SrTagRequest
import me.snowmii.streamline.StreamlineSession
import me.snowmii.streamline.StreamlineSessionTestDouble
import me.snowmii.dlss.vk.VulkanContext
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.vulkan.VkCommandBuffer
import sun.misc.Unsafe
import java.nio.file.Path

class FgEnablementFallbackTest {

	@Test
	fun `the unsupported frame auto suspends FG and a supported frame resumes it`() {
		val calls = RecordingNativeApi()
		val policy = FgSurfacePolicy()
		var supported = true
		val harness = harness(calls, policy, fgFrameSupported = { _, _ -> supported })
		val announced = mutableListOf<String>()
		val controls = RuntimeControls(harness.runtime, announced::add)

		controls.toggleFrameGeneration()
		assertTrue(policy.effective, "the supported session starts with FG effective")
		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION),
			"a supported frame must compose FG",
		)
		assertEquals(1, calls.fgConfigures.size, "the supported frame records its FG options once")

		supported = false
		assertNotNull(
			harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS),
			"the suspended frame still routes to the scene target: SR stays on",
		)
		harness.runtime.endWorldPhase()
		assertFalse(policy.effective, "an unsupported frame suspends effective FG")
		assertEquals(1, calls.fgModeOffRecords, "the suspension records the retained eOff mode exactly once")
		assertEquals(0, harness.probe.diagnostics.size, "a suspension is not a latch: no diagnostic")

		controls.cycleFgMultiplier()
		assertTrue(
			announced.last().contains("fg on (suspended)"),
			"a suspension must read as the user's mode plus its suspension: ${announced.last()}",
		)
		assertFalse(
			announced.last().contains("fg off"),
			"\"off\" is reserved for the mode the user set: ${announced.last()}",
		)

		harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS)
		harness.runtime.endWorldPhase()
		assertEquals(1, calls.fgModeOffRecords, "a suspension already in effect records nothing")
		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION),
			"an SR-only frame during the suspension must record and present",
		)
		assertEquals(1, calls.fgConfigures.size, "no FG options may record while suspended")
		assertEquals(2, calls.fgTags.size, "no FG tags may record while suspended")
		assertEquals(1, calls.handoffs, "no present handoff may record while suspended")

		supported = true
		harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS)
		harness.runtime.endWorldPhase()
		assertTrue(policy.effective, "a supported frame resumes effective FG")
		assertEquals(1, calls.fgModeOffRecords, "the resume records no mode")
		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION),
			"the resumed frame must compose FG again",
		)
		assertEquals(1, calls.fgConfigures.size, "configureFg is only called when requiredSwapchainImages changes; resuming with the same back-buffer count does not re-record")
		assertTrue(announced.last().contains("fg on"), "the readout reports the resumed mode: ${announced.last()}")
	}

	@Test
	fun `switching SR off suspends FG before the images its tags name are released`() {
		val calls = RecordingNativeApi()
		val policy = FgSurfacePolicy()
		val harness = harness(calls, policy)
		val announced = mutableListOf<String>()
		val controls = RuntimeControls(harness.runtime, announced::add)

		controls.toggleFrameGeneration()
		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION),
			"the armed frame composes FG and acquires the images its tags name",
		)

		controls.toggleEnabled()
		assertFalse(policy.effective, "SR off suspends effective FG")
		assertEquals(1, calls.fgModeOffRecords, "the release records the retained eOff mode exactly once")
		assertTrue(
			calls.order.indexOf("setFgModeOff") < calls.order.indexOf("releaseImages"),
			"the eOff record must precede the image release, got ${calls.order}",
		)

		assertNull(
			harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS),
			"an SR-off frame routes vanilla",
		)
		harness.runtime.endWorldPhase()
		assertFalse(policy.effective, "an SR-off frame must not resume FG")
		assertEquals(1, calls.fgModeOffRecords, "a suspension already in effect records nothing")

		controls.toggleEnabled()
		assertNotNull(
			harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS),
			"SR back on routes to the scene target again",
		)
		harness.runtime.endWorldPhase()
		assertTrue(policy.effective, "the first supported frame after SR returns resumes FG")
		assertEquals(1, calls.fgModeOffRecords, "the resume records no mode")
	}

	@Test
	fun `the frame after a level change discontinuity composes FG with the shared reset flag recorded`() {
		val calls = RecordingNativeApi()
		val policy = FgSurfacePolicy()
		val harness = harness(calls, policy)
		val announced = mutableListOf<String>()
		val controls = RuntimeControls(harness.runtime, announced::add)

		controls.toggleFrameGeneration()
		assertTrue(policy.effective, "the session starts with FG active")
		composeSupportedFrame(harness, stillCamera())
		assertTrue(calls.evaluations[0].resetHistory, "the first frame of a session restarts history")

		composeSupportedFrame(harness, stillCamera())
		assertFalse(calls.evaluations[1].resetHistory, "the second frame accumulates against the first")

		harness.runtime.resetHistory()

		composeSupportedFrame(harness, stillCamera())
		assertTrue(
			calls.evaluations[2].resetHistory,
			"the first composed frame after the discontinuity must record resetHistory",
		)
		assertEquals(1, calls.fgConfigures.size, "the reset frame still composes FG: no frame is classified unsupported")

		composeSupportedFrame(harness, stillCamera())
		assertFalse(calls.evaluations[3].resetHistory, "the frame after the reset accumulates normally")
		assertEquals(1, calls.fgConfigures.size, "configureFg is not called again while requiredSwapchainImages is unchanged")

		assertTrue(policy.effective, "a discontinuity is not a suspension: FG stays on")
		assertEquals(0, calls.fgModeOffRecords, "a discontinuity records no eOff mode")
		assertEquals(0, harness.probe.diagnostics.size, "a discontinuity emits no diagnostic")
		assertEquals(DlssSessionState.READY, harness.session.state, "the SR session stays READY")
		assertTrue(announced.last().contains("fg on"), "the readout still reports FG on: ${announced.last()}")
	}

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
				camera = camera,
			),
			"the supported frame must compose",
		)
		harness.runtime.endWorldPhase()
	}

	private fun stillCamera() = DlssCameraSample(
		projection = Matrix4f(),
		viewRotation = Matrix4f(),
		cameraX = 0.0,
		cameraY = 100.0,
		cameraZ = 0.0,
	)

	@Test
	fun `unsupported frames never overturn a user-off policy and no condition is permanent`() {
		val calls = RecordingNativeApi()
		val policy = FgSurfacePolicy()
		var supported = true
		val harness = harness(calls, policy, fgFrameSupported = { _, _ -> supported })

		supported = false
		harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS)
		harness.runtime.endWorldPhase()
		supported = true
		harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS)
		harness.runtime.endWorldPhase()
		assertFalse(policy.effective, "a user-off policy stays off through suspend and resume")
		assertEquals(0, calls.fgModeOffRecords, "a suspended user-off policy records no mode")

		val unhealthyCalls = RecordingNativeApi()
		val unhealthyPolicy = FgSurfacePolicy()
		var unhealthySupported = true
		val unhealthyHarness =
			harness(unhealthyCalls, unhealthyPolicy, fgFrameSupported = { _, _ -> unhealthySupported })
		assertTrue(unhealthyPolicy.setFrameGenerationActive(true), "FG must arm first")
		unhealthyCalls.status = FgState(2, 1, 0L, 0L)
		unhealthyHarness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS)
		unhealthyHarness.runtime.endWorldPhase()
		assertFalse(unhealthyPolicy.effective, "an unhealthy status suspends composition")
		assertTrue(unhealthyPolicy.userEnabled, "the user's mode - and so the swapchain policy - survives it")
		assertEquals(1, unhealthyCalls.fgModeOffRecords, "the suspension records the eOff mode once")

		unhealthyHarness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS)
		unhealthyHarness.runtime.endWorldPhase()
		assertEquals(1, unhealthyCalls.fgModeOffRecords, "a held suspension records nothing further")
		assertEquals(1, unhealthyHarness.probe.diagnostics.size, "a held suspension says nothing further")

		unhealthyCalls.status = FgState(0, 1, 0L, 0L)
		unhealthyHarness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS)
		unhealthyHarness.runtime.endWorldPhase()
		assertTrue(unhealthyPolicy.effective, "a healthy status resumes composition")
		assertEquals(2, unhealthyHarness.probe.diagnostics.size, "the resume is reported once")

		unhealthySupported = false
		unhealthyHarness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS)
		unhealthyHarness.runtime.endWorldPhase()
		assertFalse(unhealthyPolicy.effective, "an unsupported frame suspends as it always did")
		unhealthySupported = true
		unhealthyHarness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS)
		unhealthyHarness.runtime.endWorldPhase()
		assertTrue(unhealthyPolicy.effective, "and resumes")
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
	fun `a non-OK status suspends FG once with one exact diagnostic, keeps SR READY, and resumes`() {
		val calls = RecordingNativeApi()
		val policy = FgSurfacePolicy()
		val harness = harness(calls, policy)

		assertTrue(
			policy.setFrameGenerationActive(true),
			"the frame starts with FG active",
		)
		calls.status = FgState(0, 2, 0L, 0L)

		assertNotNull(
			harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS),
			"an OK-status FG frame must route to the scene target",
		)
		harness.runtime.endWorldPhase()
		assertTrue(policy.effective, "an OK status must not suspend FG")
		assertEquals(0, harness.probe.diagnostics.size, "an OK status must emit no diagnostic")
		assertEquals(0, calls.fgModeOffRecords, "an OK status must record no eOff options")

		calls.status = FgState(2, 1, 0L, 0L)
		assertNotNull(
			harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS),
			"the suspended frame must still route to the scene target: SR stays READY",
		)
		harness.runtime.endWorldPhase()

		assertFalse(policy.effective, "a non-OK status must stop FG composing")
		assertTrue(policy.userEnabled, "the user's mode must survive it, and with it the swapchain policy")
		assertEquals(1, calls.fgModeOffRecords, "the suspension must re-record the DLSS-G options in the eOff mode exactly once")
		assertEquals(
			listOf(
				"Frame generation suspended: slDLSSGGetState status=0x2 (eDLSSGStatusOk=0); " +
					"composition suspended and the eOff options retained while the status is " +
					"unhealthy, swapchain policy unchanged, and the first healthy frame resumes.",
			),
			harness.probe.diagnostics,
			"the suspension must emit exactly one diagnostic naming the status word",
		)
		assertEquals(
			DlssSessionState.READY,
			harness.session.state,
			"the status suspension must not touch the SR session: SR stays READY",
		)

		assertNotNull(
			harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS),
			"an SR-only frame during the suspension must still route to the scene target",
		)
		harness.runtime.endWorldPhase()
		assertEquals(1, calls.fgModeOffRecords, "the eOff record must stay attached to the transition")
		assertEquals(1, harness.probe.diagnostics.size, "a held suspension must not re-announce itself")

		calls.status = FgState(0, 1, 0L, 0L)
		harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS)
		harness.runtime.endWorldPhase()
		assertTrue(policy.effective, "the first healthy frame must resume composition")
		assertEquals(2, harness.probe.diagnostics.size, "the resume must be announced exactly once")
	}

	@Test
	fun `during a status suspension the frame records SR only with no FG calls and the session stays READY`() {
		val calls = RecordingNativeApi()
		val policy = FgSurfacePolicy()
		val harness = harness(calls, policy)

		assertTrue(policy.setFrameGenerationActive(true))
		calls.status = FgState(2, 1, 0L, 0L)
		harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS)
		harness.runtime.endWorldPhase()
		assertFalse(policy.effective, "the frame must suspend before its recording")

		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION),
			"an SR-only frame during the suspension must record and present",
		)
		assertEquals(
			listOf("writeMotion", "srTag", "evaluate", "present"),
			calls.order.filter { it != "waitFgInputs" && it != "setFgModeOff" },
			"during the suspension the frame must record the SR composition with no FG calls at all",
		)
		assertEquals(0, calls.fgConfigures.size, "during the suspension no FG options may record")
		assertEquals(0, calls.fgTags.size, "during the suspension no FG tags may record")
		assertEquals(0, calls.handoffs, "during the suspension no present handoff may record")
		assertEquals(
			DlssSessionState.READY,
			harness.session.state,
			"the SR session must stay READY through the suspended frame",
		)
	}

	@Test
	fun `the user toggle records the retained eOff options exactly once and re-arms with the SR session untouched`() {
		val calls = RecordingNativeApi()
		val policy = FgSurfacePolicy()
		val harness = harness(calls, policy)
		val announced = mutableListOf<String>()
		val controls = RuntimeControls(harness.runtime, announced::add)

		controls.toggleFrameGeneration()
		assertTrue(policy.effective, "the controls switch FG on")
		assertEquals(0, calls.fgModeOffRecords, "arming records no mode")
		assertTrue(announced.last().contains("fg on"), "the readout names the mode in effect: ${announced.last()}")

		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION),
			"an FG frame must record and present",
		)
		assertEquals(1, calls.fgConfigures.size, "the FG frame records its options once")

		controls.toggleFrameGeneration()
		assertFalse(policy.effective, "the controls switch FG off")
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

		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION),
			"an SR-only frame after the user toggle must record and present",
		)
		assertEquals(1, calls.fgConfigures.size, "no FG options may record after the toggle")
		assertEquals(2, calls.fgTags.size, "no FG tags may record after the toggle")
		assertEquals(1, calls.handoffs, "no present handoff may record after the toggle")
		assertEquals(1, calls.fgModeOffRecords, "the eOff record must stay attached to the one transition")

		controls.toggleFrameGeneration()
		assertTrue(policy.effective, "a user-off policy re-arms")
		assertEquals(1, calls.fgModeOffRecords, "re-arming records no mode")
		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION),
			"the re-armed frame must compose FG again",
		)
		assertEquals(1, calls.fgConfigures.size, "configureFg is only called when requiredSwapchainImages changes; re-arming with the same back-buffer count does not re-record")

		controls.toggleFrameGeneration()
		assertFalse(policy.effective)
		assertEquals(2, calls.fgModeOffRecords, "each off transition records exactly once")
		assertEquals(listOf(0, 0), calls.fgModeValues, "each eOff record passes mode zero")
		assertEquals(
			DlssSessionState.READY,
			harness.session.state,
			"the SR session stays READY through both user cycles",
		)
	}

	@Test
	fun `a status suspension leaves the user's FG mode theirs to switch off and back on`() {
		val calls = RecordingNativeApi()
		val policy = FgSurfacePolicy()
		val harness = harness(calls, policy)
		val announced = mutableListOf<String>()
		val controls = RuntimeControls(harness.runtime, announced::add)

		controls.toggleFrameGeneration()
		assertTrue(policy.effective, "the session starts with FG active")
		calls.status = FgState(2, 1, 0L, 0L)
		assertNotNull(
			harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS),
			"the suspended frame must still route to the scene target: SR stays READY",
		)
		harness.runtime.endWorldPhase()
		assertFalse(policy.effective, "the bad status must suspend composition")
		assertEquals(1, calls.fgModeOffRecords, "the suspension records the eOff mode")
		assertEquals(1, harness.probe.diagnostics.size, "the suspension emits its one exact diagnostic")

		controls.toggleFrameGeneration()
		assertFalse(policy.userEnabled, "the user's off must disarm")
		assertTrue(announced.last().contains("fg off"), "the readout reports the state actually in effect: ${announced.last()}")

		controls.toggleFrameGeneration()
		assertTrue(policy.userEnabled, "the user's on must re-arm: no verdict stands in the way")
		calls.status = FgState(0, 1, 0L, 0L)
		harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS)
		harness.runtime.endWorldPhase()
		assertTrue(policy.effective, "a healthy frame on a re-armed policy composes")
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

		assertFalse(adapter.recordFrameGenerationOff(), "a session that is not READY must not record the eOff mode")
		assertEquals(0, native.setFgModeValues.size)

		assertTrue(adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")) != null)
		assertTrue(adapter.recordFrameGenerationOff(), "a READY session must record the eOff mode")
		assertEquals(listOf(0), native.setFgModeValues, "the eOff record must pass mode zero to the bridge")

		native.setFgModeResult = StreamlineSession.SUCCESS_RESULT + 1
		assertFalse(adapter.recordFrameGenerationOff(), "a refused eOff record must answer false")
		assertEquals(DlssSessionState.READY, session.state, "the refused eOff record must not latch the SR session")
	}

	private fun harness(
		calls: RecordingNativeApi,
		policy: FgSurfacePolicy,
		fgFrameSupported: (Boolean, Dimensions) -> Boolean = { normalInWorldFrame, outputDimensions ->
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
			0,
			0,
			0,
			0,
			{
				counters.buffers++
				fakeCommandBuffer()
			},
			{ counters.submits++ },
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
				allocate = { width, height -> HeadlessRenderTarget(width, height) },
				release = {},
			),
			startup = { RENDER_DIMENSIONS },
			frameEvaluation = evaluation,
			frameGeneration = policy,
			bridge = adapter,
			diagnostics = { probe.diagnostics += it },
			fgFrameSupported = fgFrameSupported,
		)
		return Harness(runtime, session, evaluation, probe)
	}

	private fun fakeCommandBuffer(): VkCommandBuffer {
		val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
		unsafeField.isAccessible = true
		val unsafe = unsafeField.get(null) as Unsafe
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

	private class RecordingNativeApi : StreamlineSessionTestDouble() {
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
		): Int = StreamlineSession.SUCCESS_RESULT

		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int): Dimensions =
			RENDER_DIMENSIONS

		override fun configureSuperResolution(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		): Int = StreamlineSession.SUCCESS_RESULT

		override fun acquireImages(): EvaluationImages = EvaluationImages(
			ImageBinding(401L, 402L, 124),
			ImageBinding(501L, 502L, 37),
		)

		override fun releaseImages(): Int {
			order += "releaseImages"
			return StreamlineSession.SUCCESS_RESULT
		}

		override fun waitDeviceIdle(): Int = StreamlineSession.SUCCESS_RESULT

		override fun frameTimings(): FrameTimings? = null

		override fun waitFgInputsIdle(): Int {
			order += "waitFgInputs"
			return StreamlineSession.SUCCESS_RESULT
		}

		override fun queryFgState(): FgState = status

		override fun setFgMode(fgEnabled: Int): Int {
			fgModeValues += fgEnabled
			fgModeOffRecords++
			order += "setFgModeOff"
			return StreamlineSession.SUCCESS_RESULT
		}

		override fun configureFg(numBackBuffers: Int): Int {
			fgConfigures += numBackBuffers
			order += "configureFg"
			return StreamlineSession.SUCCESS_RESULT
		}

		override fun tagFrameGenerationResources(request: FgTagRequest): Int {
			fgTags += request
			order += "fgTag"
			return StreamlineSession.SUCCESS_RESULT
		}

		override fun recordPresentHandoff(): Int {
			handoffs++
			order += "handoff"
			return StreamlineSession.SUCCESS_RESULT
		}

		override fun tagSrResources(request: SrTagRequest): Int {
			order += "srTag"
			return StreamlineSession.SUCCESS_RESULT
		}

		override fun writeMotion(request: MotionRequest): Int {
			order += "writeMotion"
			return StreamlineSession.SUCCESS_RESULT
		}

		override fun presentOutput(target: PresentTarget): Int {
			order += "present"
			return StreamlineSession.SUCCESS_RESULT
		}

		override fun evaluateSuperResolution(request: EvaluationRequest): Int {
			evaluations += request
			order += "evaluate"
			return StreamlineSession.SUCCESS_RESULT
		}
	}

	private class FakeNative : StreamlineSessionTestDouble() {
		var setFgModeResult = StreamlineSession.SUCCESS_RESULT
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
		): Int = StreamlineSession.SUCCESS_RESULT

		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int) =
			Dimensions(1280, 720)

		override fun configureSuperResolution(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		): Int = StreamlineSession.SUCCESS_RESULT

		override fun acquireImages(): EvaluationImages = error("unexpected acquireImages")
		override fun releaseImages(): Int = error("unexpected releaseImages")
		override fun waitDeviceIdle(): Int = error("unexpected waitDeviceIdle")
		override fun frameTimings(): FrameTimings = error("unexpected frameTimings")
		override fun writeMotion(request: MotionRequest): Int = error("unexpected writeMotion")
		override fun presentOutput(target: PresentTarget): Int = error("unexpected presentOutput")
		override fun evaluateSuperResolution(request: EvaluationRequest): Int = error("unexpected evaluate")
	}

	private class HeadlessRenderTarget(width: Int, height: Int) : RenderTarget("fake", GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT) {
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
		val RENDER_DIMENSIONS = Dimensions(1280, 720)
		val OUTPUT_DIMENSIONS = Dimensions(2560, 1440)
		const val DESTINATION = 999L
	}
}
