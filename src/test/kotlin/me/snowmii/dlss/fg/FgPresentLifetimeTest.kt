package me.snowmii.dlss.fg

import java.nio.file.Path
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.bridge.DlssEvaluationImages
import me.snowmii.dlss.bridge.DlssFrameTimings
import me.snowmii.dlss.bridge.EvaluationRequest
import me.snowmii.dlss.bridge.ExtensionBootstrap
import me.snowmii.dlss.bridge.FgTagRequest
import me.snowmii.dlss.bridge.FillVelocityRequest
import me.snowmii.dlss.bridge.ImageBinding
import me.snowmii.dlss.bridge.MotionRequest
import me.snowmii.dlss.bridge.Native
import me.snowmii.dlss.bridge.NativeApi
import me.snowmii.dlss.bridge.PresentTarget
import me.snowmii.dlss.bridge.SrTagRequest
import me.snowmii.dlss.bridge.VulkanContext
import me.snowmii.dlss.mrt.MotionVectorRoute
import me.snowmii.dlss.render.DlssFrameMotion
import me.snowmii.dlss.render.DlssJitter
import me.snowmii.dlss.render.DlssJitterOffset
import me.snowmii.dlss.render.FgFrameInputs
import me.snowmii.dlss.render.FrameEvaluation
import me.snowmii.dlss.render.RenderRuntime
import me.snowmii.dlss.render.SceneResources
import me.snowmii.dlss.render.SceneTarget
import me.snowmii.dlss.session.DlssNativeStage
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssSessionState
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.session.SRMode
import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.vulkan.VkCommandBuffer

/**
 * M-11 present-lifetime rung: while FG is active, the frame's start waits for the previously
 * presented frame's DLSS-G input processing to complete - the eBlockNoClientQueues fence
 * discipline - BEFORE the frame rewrites any FG-tagged input, and that wait degrades through
 * the session state like every other native stage.
 *
 * The production seam is [RenderRuntime.beginWorldPhase]: the world phase renders into the
 * scene depth and the split renders into the HUD-less and UI targets after it returns, so the
 * wait has to run there - the earliest point the mod can block before rewriting the tagged
 * inputs - and the native bridge reads `DLSSGState::inputsProcessingCompletionFence` via
 * `slDLSSGGetState` and waits on the Vulkan device. The native refusal gates (no ready
 * session, no recorded DLSS-G options) and the null-fence no-op are the bridge's contract;
 * the headless fixture that proves them live is a later slice. What this rung proves is the
 * production wiring: the wait runs once per FG-active frame before any recording, never on an
 * FG-off frame, the one refusal production can legitimately hit - no options recorded yet, on
 * the first FG frame - is benign, a genuine failure latches the session to vanilla, and the
 * native entry refuses before any Streamline session exists.
 */
class FgPresentLifetimeTest {

	@Test
	fun `an FG-active frame waits for the previous frame's input processing before its own rewrites`() {
		val calls = RecordingNative()
		val policy = FgSurfacePolicy()
		val harness = harness(calls, policy)

		policy.setFrameGenerationActive(true)

		// Frame A: the wait runs at the frame's start, before the first rewrite the recording
		// owns (the motion pass), and the frame hands off when it presents.
		assertTrue(
			harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS) != null,
			"an FG-active eligible frame must route to the scene target",
		)
		harness.runtime.endWorldPhase()
		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION, MotionVectorRoute.CAMERA_ONLY),
			"the FG frame must record and hand off",
		)

		// Frame B: the wait must run again at the new frame's start - after frame A handed
		// off and before frame B's first rewrite - because frame B is about to overwrite the
		// inputs frame A's present is still processing.
		harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS)
		harness.runtime.endWorldPhase()
		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION, MotionVectorRoute.CAMERA_ONLY),
			"the second FG frame must record and hand off",
		)

		assertEquals(
			listOf(
				"waitFgInputs", "writeMotion", "configureFg", "fgTag", "srTag", "evaluate", "fgTag", "handoff", "present",
				"waitFgInputs", "writeMotion", "configureFg", "fgTag", "srTag", "evaluate", "fgTag", "handoff", "present",
			),
			calls.order,
			"each FG-active frame must wait for the previous frame's input processing at its " +
				"start - after the previous handoff, before its own first rewrite - and the wait " +
				"must not disturb the composed recording",
		)
		assertEquals(2, calls.waits, "two FG-active frames must wait exactly once each")
	}

	@Test
	fun `an FG-off frame makes no input-processing wait`() {
		val calls = RecordingNative()
		val harness = harness(calls, FgSurfacePolicy())

		harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS)
		harness.runtime.endWorldPhase()
		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION, MotionVectorRoute.CAMERA_ONLY),
			"an FG-off frame must still record the SR frame",
		)

		assertEquals(
			listOf("writeMotion", "srTag", "evaluate", "present"),
			calls.order,
			"an FG-off frame must make no wait and no FG calls at all",
		)
		assertEquals(0, calls.waits)
	}

	@Test
	fun `the options-less refusal on the first FG frame is benign`() {
		val calls = RecordingNative(waitResult = FAIL_INVALID_PARAMETER)
		val policy = FgSurfacePolicy()
		val harness = harness(calls, policy)

		policy.setFrameGenerationActive(true)

		// The first FG frame's start runs before any configureFg: no DLSS-G options have
		// recorded yet, the bridge refuses the wait, and there is nothing to wait for because
		// no frame has been presented through DLSS-G. The refusal must not latch the session -
		// that would kill FG on its first frame.
		assertTrue(
			harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS) != null,
			"a refused wait on the first FG frame must not abort the frame",
		)
		assertEquals(DlssSessionState.READY, harness.session.state, "the options-less refusal must not latch the session")
		assertNull(harness.session.failure, "the options-less refusal must not record a failure")
		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION, MotionVectorRoute.CAMERA_ONLY),
			"the first FG frame must still record and hand off",
		)
		assertEquals(1, calls.waits, "the wait must have been asked exactly once")
	}

	@Test
	fun `a failed input-processing wait latches the session to vanilla fallback`() {
		val calls = RecordingNative(waitResult = FAIL)
		val policy = FgSurfacePolicy()
		val harness = harness(calls, policy)

		policy.setFrameGenerationActive(true)

		assertNull(
			harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS),
			"a genuine wait failure must degrade the frame to the vanilla main target",
		)
		assertEquals(DlssSessionState.FALLBACK_LATCHED, harness.session.state)
		assertEquals(DlssNativeStage.WAIT_FG_INPUTS, harness.session.failure?.stage)
		assertEquals(
			FAIL,
			harness.session.failure?.resultCode,
			"the latched failure must carry the native result the wait answered",
		)
	}

	@Test
	fun `the input wait refuses before any Streamline session exists`() {
		// Pre-init: this fork's module has never bootstrapped, so the wait seam has no
		// Streamline session to answer through - the native pre-ready refusal. The check runs
		// on a throwaway bridge and the module's bootstrap state is what it asserts against.
		Native.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
			assertEquals(
				FAIL_NOT_INITIALIZED,
				bridge.waitFgInputsIdle(),
				"the input wait before bootstrap must answer FAIL_NotInitialized",
			)
		}
	}

	/**
	 * Builds the production present-lifetime seam over a recording fake: a READY session
	 * through the real [LifecycleAdapter], the runtime's beginWorldPhase wired to the
	 * adapter's wait exactly like [RenderRuntime.forMinecraft], and the composed frame
	 * evaluation over the same adapter.
	 */
	private fun harness(
		calls: RecordingNative,
		policy: FgSurfacePolicy,
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
		)
		return Harness(runtime, session, calls, evaluation)
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

	private class Harness(
		val runtime: RenderRuntime,
		val session: DlssSession,
		val calls: RecordingNative,
		val evaluation: FrameEvaluation,
	)

	private class Counters {
		var buffers = 0
		var submits = 0
	}

	/**
	 * Records every per-frame native call in submission order so the present-lifetime seam is
	 * assertable off the render thread; everything else is the lifecycle [LifecycleAdapter]
	 * drives to READY.
	 */
	private class RecordingNative(
		private val waitResult: Int = NativeApi.SUCCESS_RESULT,
	) : NativeApi {
		val order = mutableListOf<String>()
		var waits = 0

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
			waits++
			order += "waitFgInputs"
			return waitResult
		}

		override fun configureFg(numBackBuffers: Int): Int {
			order += "configureFg"
			return NativeApi.SUCCESS_RESULT
		}

		override fun tagFgResources(request: FgTagRequest): Int {
			order += "fgTag"
			return NativeApi.SUCCESS_RESULT
		}

		override fun presentHandoff(): Int {
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
			order += "evaluate"
			return NativeApi.SUCCESS_RESULT
		}
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

		/** The engine's output-sized main target image the frame's SR output copy records into. */
		const val DESTINATION = 900L

		/** NVSDK_NGX_Result_Fail = 0xBAD00000 | 1. */
		private val FAIL = 0xBAD00001.toInt()

		/** NVSDK_NGX_Result_FAIL_InvalidParameter = NVSDK_NGX_Result_Fail | 5 (0xBAD00000 | 5). */
		private val FAIL_INVALID_PARAMETER = 0xBAD00005.toInt()

		/** NVSDK_NGX_Result_FAIL_NotInitialized = NVSDK_NGX_Result_Fail | 7 (0xBAD00000 | 7). */
		private val FAIL_NOT_INITIALIZED = 0xBAD00007.toInt()
	}
}
