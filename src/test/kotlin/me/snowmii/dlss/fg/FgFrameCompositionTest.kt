package me.snowmii.dlss.fg

import java.nio.file.Path
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.bridge.DlssEvaluationImages
import me.snowmii.dlss.bridge.DlssFrameTimings
import me.snowmii.dlss.bridge.EvaluationRequest
import me.snowmii.dlss.bridge.FgTagRequest
import me.snowmii.dlss.bridge.FillVelocityRequest
import me.snowmii.dlss.bridge.ImageBinding
import me.snowmii.dlss.bridge.MotionRequest
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
import me.snowmii.dlss.render.SceneResources
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.session.SRMode
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.vulkan.VkCommandBuffer

/**
 * M-11 production-composition rung: an active FG frame's production recording composes the
 * DLSS-G record around the SR evaluation - FG options and FG tag before the SR tag, the SR
 * evaluation, the FG re-tag, and one present handoff, all on one command buffer under one
 * retained frame token - while an inactive frame, or an active frame without FG inputs,
 * records SR only and makes no FG calls at all.
 *
 * The M-11 headless rungs proved the native composition on a live session (PresentIntegrationTest
 * records the FG tag, SR tag, evaluation, FG re-tag, and handoff sequence against the real
 * Streamline runtime). This rung proves the production caller records that exact sequence:
 * [FrameEvaluation] is the mod's only command-buffer owner, and until this wiring nothing in
 * production called configureFg, tagFgResources, or presentHandoff - the intercepted Present
 * had no FG-tagged frame to consume. What is asserted here is the recording order and the
 * requests it hands the adapter, on a fake adapter and context, so the whole sequence is
 * verifiable off the render thread exactly as the SR composition rungs drive it.
 */
class FgFrameCompositionTest {

	@Test
	fun `an active FG frame records configure FG tag SR tag evaluation FG re-tag and one handoff on one buffer`() {
		val calls = RecordingNative()
		val policy = FgSurfacePolicy()
		val harness = harness(calls, policy, fgInputs())

		policy.setFrameGenerationActive(true)

		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION, MotionVectorRoute.CAMERA_ONLY),
			"an active FG frame with both output-sized targets must compose and hand off",
		)
		assertEquals(
			listOf("writeMotion", "configureFg", "fgTag", "srTag", "evaluate", "fgTag", "handoff", "present"),
			calls.order,
			"the FG tag must record before the SR tag, the re-tag after the evaluation, and " +
				"the handoff exactly once as the frame's terminal record",
		)
		assertEquals(1, calls.handoffs, "one active FG frame must hand off exactly once")
		assertEquals(1, harness.buffers, "one frame must record on exactly one command buffer")
		assertEquals(1, harness.submits, "the single buffer must be submitted")
		assertEquals(
			1,
			calls.fgTags.map { it.commandBuffer }.distinct().size,
			"both FG tag records must land on the same command buffer as the rest of the frame",
		)
		// The FG tag names the frame's render-sized depth and the two output-sized targets the
		// runtime resolved, exactly as the native side's tag contract reads them.
		assertEquals(
			FgTagRequest(
				commandBuffer = calls.fgTags.first().commandBuffer,
				depth = scene().depth,
				hudless = fgInputs().hudless,
				ui = fgInputs().ui,
			),
			calls.fgTags.first(),
			"the pre-SR FG tag must name the frame's depth and the resolved HUD-less and UI targets",
		)
		assertEquals(
			calls.fgTags.first(),
			calls.fgTags.last(),
			"the post-evaluation FG re-tag must name the same resources as the pre-SR tag",
		)
		assertEquals(
			FgSurfacePolicy.DEFAULT_DECLARED_BACK_BUFFERS,
			calls.fgConfigures.single(),
			"the frame's FG options must record with the back-buffer count the swapchain policy declares",
		)
	}

	@Test
	fun `an inactive FG frame records the SR frame with no FG calls`() {
		val calls = RecordingNative()
		val harness = harness(calls, FgSurfacePolicy(), fgInputs())

		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION, MotionVectorRoute.CAMERA_ONLY),
			"an FG-off frame must still record the SR frame",
		)
		assertEquals(
			listOf("writeMotion", "srTag", "evaluate", "present"),
			calls.order,
			"an FG-off frame must make no FG calls at all: no options, no tags, no handoff",
		)
		assertEquals(0, calls.handoffs)
		assertEquals(0, calls.fgTags.size)
		assertEquals(0, calls.fgConfigures.size)
	}

	@Test
	fun `an active FG frame without FG inputs records the SR frame with no FG calls`() {
		val calls = RecordingNative()
		val policy = FgSurfacePolicy()
		val harness = harness(calls, policy, null)

		policy.setFrameGenerationActive(true)

		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION, MotionVectorRoute.CAMERA_ONLY),
			"an active FG frame whose targets are not resolved yet must still record the SR frame",
		)
		assertEquals(
			listOf("writeMotion", "srTag", "evaluate", "present"),
			calls.order,
			"a frame without resolved FG inputs must make no FG calls: a tag naming a target " +
				"that does not exist at the output size is worse than one frame without FG",
		)
		assertEquals(0, calls.handoffs)
	}

	@Test
	fun `a refused FG tag skips the frame's SR evaluation and reports failure`() {
		val calls = RecordingNative(failFgTag = true)
		val policy = FgSurfacePolicy()
		val harness = harness(calls, policy, fgInputs())

		policy.setFrameGenerationActive(true)

		assertFalse(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION, MotionVectorRoute.CAMERA_ONLY),
			"a refused FG tag must fail the frame like any other refused stage",
		)
		assertEquals(
			listOf("writeMotion", "configureFg", "fgTag"),
			calls.order,
			"a frame whose FG tag was refused must not record a partial SR+FG set - the SR " +
				"evaluation never runs against FG-tagged resources the present path cannot consume",
		)
		assertEquals(1, harness.submits, "a failed recording still hands its buffer back")
	}

	/**
	 * Builds the production evaluation seam over a recording fake: a READY session through the
	 * real [LifecycleAdapter], a fake context that counts buffer recordings and submissions,
	 * and the runtime's FG inputs supplier resolved per frame.
	 */
	private fun harness(
		calls: RecordingNative,
		policy: FgSurfacePolicy,
		inputs: FgFrameInputs?,
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
		return Harness(
			evaluation = FrameEvaluation(
				adapter,
				{ context },
				frameGeneration = policy,
				fgInputs = { inputs },
			),
			calls = calls,
			counters = counters,
		)
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
		val evaluation: FrameEvaluation,
		val calls: RecordingNative,
		private val counters: Counters,
	) {
		val buffers: Int
			get() = counters.buffers
		val submits: Int
			get() = counters.submits
	}

	private class Counters {
		var buffers = 0
		var submits = 0
	}

	/**
	 * Records every per-frame native call in submission order so the FG composition seam is
	 * assertable off the render thread; everything else is the lifecycle [LifecycleAdapter]
	 * drives to READY.
	 */
	private class RecordingNative(
		private val failFgTag: Boolean = false,
	) : NativeApi {
		val order = mutableListOf<String>()
		val fgTags = mutableListOf<FgTagRequest>()
		val fgConfigures = mutableListOf<Int>()
		var handoffs = 0

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

		override fun configureFg(numBackBuffers: Int): Int {
			fgConfigures += numBackBuffers
			order += "configureFg"
			return NativeApi.SUCCESS_RESULT
		}

		override fun tagFgResources(request: FgTagRequest): Int {
			fgTags += request
			order += "fgTag"
			return if (failFgTag) NativeApi.SUCCESS_RESULT + 1 else NativeApi.SUCCESS_RESULT
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
			order += "evaluate"
			return NativeApi.SUCCESS_RESULT
		}
	}

	private companion object {
		val RENDER_DIMENSIONS = DlssDimensions(1280, 720)
		val OUTPUT_DIMENSIONS = DlssDimensions(2560, 1440)

		/** The engine's output-sized main target image the frame's SR output copy records into. */
		const val DESTINATION = 900L
	}
}
