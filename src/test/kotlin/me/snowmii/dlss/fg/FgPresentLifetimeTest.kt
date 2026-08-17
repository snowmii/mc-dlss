package me.snowmii.dlss.fg

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import me.snowmii.dlss.mrt.MotionVectorRoute
import me.snowmii.dlss.render.*
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.session.SRMode
import me.snowmii.streamline.*
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.lwjgl.vulkan.VkCommandBuffer
import java.nio.file.Path

/**
 * Present-lifetime behavior after the MFG latency change: production no longer waits on
 * `DLSSGState::inputsProcessingCompletionFence` at the frame's start, so neither FG-active nor
 * FG-off frames make that wait. The recorded call order checks the wiring.
 *
 * The wait was removed at [RenderRuntime.beginWorldPhase] because it cost 10-11ms of every
 * 13ms FG frame; it is required only under `eBlockNoClientQueues`, and the recorded options
 * are `eBlockPresentingClientQueue`, under which the guide makes it recommended rather than
 * required for a single-queue application. That reasoning lives at the production seam.
 *
 * Native ABI and timeline-semaphore behavior are covered by streamline integration tests; this
 * class covers only mod-owned production wiring through [StreamlineSessionTestDouble].
 */
class FgPresentLifetimeTest {

	@Test
	fun `an FG-active frame makes no input-processing wait`() {
		val calls = RecordingNativeApi()
		val policy = FgSurfacePolicy()
		val harness = harness(calls, policy)

		policy.setFrameGenerationActive(true)

		assertTrue(
			harness.runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = OUTPUT_DIMENSIONS) != null,
			"an FG-active eligible frame must route to the scene target",
		)
		harness.runtime.endWorldPhase()
		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION, MotionVectorRoute.CAMERA_ONLY),
			"the FG frame must record and hand off",
		)

		assertEquals(
			listOf("writeMotion", "configureFg", "fgTag", "srTag", "evaluate", "present", "fgTag", "handoff"),
			calls.order,
			"an FG-active frame must compose without the input-processing wait the latency fix removed",
		)
		assertEquals(0, calls.waits, "no frame waits on the input-processing fence any more")
	}

	@Test
	fun `an FG-off frame makes no input-processing wait`() {
		val calls = RecordingNativeApi()
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

	/**
	 * Builds the production present-lifetime seam over a recording fake: a READY session
	 * through the real [LifecycleAdapter], the runtime's beginWorldPhase wired to the
	 * adapter's wait exactly like [RenderRuntime.forMinecraft], and the composed frame
	 * evaluation over the same adapter.
	 */
	private fun harness(
		calls: RecordingNativeApi,
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
		)
		return Harness(runtime, session, evaluation)
	}

	/** Fake command buffer for mod-owned recording seam; no Vulkan device is opened. */
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
	private class RecordingNativeApi(
		private val waitResult: Int = StreamlineSession.SUCCESS_RESULT,
	) : StreamlineSessionTestDouble() {
		val order = mutableListOf<String>()
		var waits = 0

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

		override fun releaseImages(): Int = StreamlineSession.SUCCESS_RESULT

		override fun waitDeviceIdle(): Int = StreamlineSession.SUCCESS_RESULT

		override fun frameTimings(): FrameTimings? = null

		override fun waitFgInputsIdle(): Int {
			waits++
			order += "waitFgInputs"
			return waitResult
		}

		override fun configureFg(numBackBuffers: Int): Int {
			order += "configureFg"
			return StreamlineSession.SUCCESS_RESULT
		}

		override fun tagFrameGenerationResources(request: FgTagRequest): Int {
			order += "fgTag"
			return StreamlineSession.SUCCESS_RESULT
		}

		override fun recordPresentHandoff(): Int {
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

		override fun fillVelocity(request: FillVelocityRequest): Int {
			order += "fillVelocity"
			return StreamlineSession.SUCCESS_RESULT
		}

		override fun presentOutput(target: PresentTarget): Int {
			order += "present"
			return StreamlineSession.SUCCESS_RESULT
		}

		override fun evaluateSuperResolution(request: EvaluationRequest): Int {
			order += "evaluate"
			return StreamlineSession.SUCCESS_RESULT
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
		val RENDER_DIMENSIONS = Dimensions(1280, 720)
		val OUTPUT_DIMENSIONS = Dimensions(2560, 1440)

		/** The engine's output-sized main target image the frame's SR output copy records into. */
		const val DESTINATION = 900L
	}
}
