package me.snowmii.dlss.mrt

import java.nio.file.Path
import kotlin.io.path.readText
import me.snowmii.dlss.nativeSource
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.bridge.DlssEvaluationImages
import me.snowmii.dlss.bridge.DlssFrameTimings
import me.snowmii.dlss.bridge.EvaluationRequest
import me.snowmii.dlss.bridge.ImageBinding
import me.snowmii.dlss.bridge.MotionRequest
import me.snowmii.dlss.bridge.NativeApi
import me.snowmii.dlss.bridge.PresentTarget
import me.snowmii.dlss.bridge.SrTagRequest
import me.snowmii.dlss.bridge.VulkanContext
import me.snowmii.dlss.render.DlssCameraSample
import me.snowmii.dlss.render.DlssFrameMotion
import me.snowmii.dlss.render.DlssJitter
import me.snowmii.dlss.render.DlssJitterOffset
import me.snowmii.dlss.render.FrameEvaluation
import me.snowmii.dlss.render.RenderRuntime
import me.snowmii.dlss.render.SceneResources
import me.snowmii.dlss.render.SceneTarget
import me.snowmii.dlss.render.WorldPhase
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.session.SRMode
import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.vulkan.VkCommandBuffer

/**
 * The M-6 route gate: the velocity-MRT frame feeds the scene's RG16_FLOAT velocity companion
 * to Streamline as the motion source and stops recording the compute camera-motion writer,
 * while the camera-only frame preserves the existing compute writer and the native motion
 * image path byte for byte.
 *
 * The M-4/M-5 velocity payload (terrain and stress writers) is dead data until this lands:
 * every DLSS frame still records `writeMotion` and tags the native motion image as
 * `kBufferTypeMotionVectors`, so nothing the MRT writers produced ever reached DLSS. This test
 * pins the activation: the route decided by [WorldPhase] is handed into the evaluation with
 * the velocity view, [FrameEvaluation] skips `writeMotion` on the velocity route and carries
 * the velocity companion on the tag request, and the native tag call selects that companion as
 * the motion-vector buffer instead of the module's motion image.
 *
 * The whole test is pure JVM: the frame path runs against a recording [NativeApi] fake and the
 * native behaviour is pinned by source text, exactly like the other M-4/M-5 route tests. The
 * live Streamline tests stay on the camera-only default, so nothing here changes what they
 * exercise.
 */
class MotionVectorRouteTest {
	private val mainTarget = fakeMainTarget()


	@Test
	fun `the velocity route tags the scene velocity companion and skips the compute writer`() {
		val calls = RecordingNative(RENDER_DIMENSIONS)
		val evaluation = evaluation(calls)
		val velocity = ImageBinding(11L, 12L, 124)

		assertTrue(
			evaluation.evaluateFrame(
				scene(),
				jitter(),
				motion(),
				route = MotionVectorRoute.VELOCITY_MRT,
				velocity = velocity,
			),
			"the velocity-route frame must record through to the evaluation",
		)
		assertTrue(calls.writeMotion.isEmpty(), "the velocity route must not record the compute camera-motion writer")
		assertEquals(velocity, calls.tags.single().velocity, "the frame's velocity companion must cross on the tag request")
		assertEquals(1, calls.evaluations.size, "the frame must still evaluate")
	}

	@Test
	fun `the camera-only route keeps the compute writer and the native motion image path`() {
		val calls = RecordingNative(RENDER_DIMENSIONS)
		val evaluation = evaluation(calls)

		// The default route and absent velocity are exactly what the live Streamline tests and
		// the SR evaluation seam exercise today.
		assertTrue(evaluation.evaluateFrame(scene(), jitter(), motion()))
		assertEquals(1, calls.writeMotion.size, "the camera-only route must keep the compute writer")
		assertEquals(
			ImageBinding(0, 0, 0),
			calls.tags.single().velocity,
			"the camera-only tag must leave the velocity absent so the native motion image tags",
		)
		assertEquals(1, calls.evaluations.size)
	}

	@Test
	fun `a stray velocity on the camera-only route cannot replace the native motion image path`() {
		val calls = RecordingNative(RENDER_DIMENSIONS)
		val evaluation = evaluation(calls)

		// WorldPhase never produces this shape, but a caller that does must not silently move
		// the camera-only route onto a velocity tag the compute writer never filled.
		assertTrue(
			evaluation.evaluateFrame(
				scene(),
				jitter(),
				motion(),
				route = MotionVectorRoute.CAMERA_ONLY,
				velocity = ImageBinding(11L, 12L, 124),
			),
		)
		assertEquals(1, calls.writeMotion.size)
		assertEquals(
			ImageBinding(0, 0, 0),
			calls.tags.single().velocity,
			"the camera-only route must always tag the native motion image, never a carried velocity",
		)
	}

	@Test
	fun `the velocity route without a velocity companion is not evaluated`() {
		val calls = RecordingNative(RENDER_DIMENSIONS)
		val evaluation = evaluation(calls)

		// A velocity-route frame with no motion source at all must skip rather than hand DLSS
		// an image the compute writer was retired from filling.
		assertFalse(evaluation.evaluateFrame(scene(), jitter(), motion(), route = MotionVectorRoute.VELOCITY_MRT))
		assertTrue(calls.writeMotion.isEmpty())
		assertTrue(calls.tags.isEmpty())
		assertTrue(calls.evaluations.isEmpty())
	}

	@Test
	fun `the world phase hands the velocity route and the velocity view into the evaluation`() {
		val runtime = velocityRuntime()
		var handed: Pair<MotionVectorRoute, GpuTextureView?>? = null
		val phase = worldPhase(runtime) { _, _, _, _, route, velocity ->
			handed = route to velocity
			true
		}

		phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = cameraSample())
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		phase.end()

		val (route, view) = checkNotNull(handed) { "an eligible frame must reach the evaluation" }
		assertEquals(MotionVectorRoute.VELOCITY_MRT, route)
		assertNotNull(view, "the velocity route must hand the scene velocity companion")
		assertEquals(GpuFormat.RG16_FLOAT, view!!.texture().getFormat())
	}

	@Test
	fun `the camera-only phase hands camera-only and no velocity view`() {
		val runtime = velocityRuntime()
		runtime.observeWorldPipeline(
			MotionVectorPipeline(
				"example:pipeline/waving_terrain",
				listOf(MotionVectorShader("example:core/waving_terrain", "example")),
			),
		)
		assertEquals(MotionVectorRoute.CAMERA_ONLY, runtime.motionVectorRoute)
		var handed: Pair<MotionVectorRoute, GpuTextureView?>? = null
		val phase = worldPhase(runtime) { _, _, _, _, route, velocity ->
			handed = route to velocity
			true
		}

		phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = cameraSample())
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		phase.end()

		val (route, view) = checkNotNull(handed) { "a camera-only frame still reaches the evaluation" }
		assertEquals(MotionVectorRoute.CAMERA_ONLY, route)
		assertNull(view, "the camera-only route must hand no velocity view")
	}

	@Test
	fun `the tag ABI carries the velocity image and the native tag call selects it as motion`() {
		val header = nativeSource("mc_dlss.h")
		val tagStruct = header.substringAfter("typedef struct McDlssTagInfo {")
			.substringBefore("} McDlssTagInfo;")
		assertTrue(
			tagStruct.contains("McDlssImage velocity;"),
			"McDlssTagInfo must carry the engine's velocity image",
		)

		val java = Path.of("")
			.toAbsolutePath()
			.resolve("src/main/java/me/snowmii/dlss/bridge/Native.java")
			.readText()
		assertTrue(java.contains("IMAGE_LAYOUT.withName(\"velocity\")"), "the FFM tag layout must mirror the velocity field")
		assertTrue(java.contains("TAG_VELOCITY_VIEW"))
		assertTrue(java.contains("TAG_VELOCITY_IMAGE"))
		assertTrue(java.contains("TAG_VELOCITY_FORMAT"))
		assertTrue(java.contains("writeImage(info, TAG_VELOCITY_VIEW, TAG_VELOCITY_IMAGE, TAG_VELOCITY_FORMAT, request.getVelocity())"))

		val sl = nativeSource("internal/sl_dlss.cpp")
		// The motion-source selection: the ABI-carried velocity companion when present, the
		// module's own motion image otherwise.
		assertTrue(sl.contains("valid_image(info.velocity)"))
		assertTrue(sl.contains("hasVelocity"))
		assertTrue(sl.contains("kBufferTypeMotionVectors"))
		assertTrue(sl.contains("velocityResource"))
		// The velocity resource names the layout the engine actually leaves it in; the module
		// cannot transition it on the evaluate path, so the tag must declare the resting layout
		// per the manual-hooking contract rather than kDlssInputLayout.
		assertTrue(sl.contains("kEngineRestingLayout"))
	}

	@Test
	fun `the velocity route opens the native timing chain with a skipped motion stage`() {
		val calls = RecordingNative(RENDER_DIMENSIONS)
		val evaluation = evaluation(calls)

		// The frame's GPU timing opens where the frame's recording does, and the evaluate/present
		// timing marks are only valid inside an opened slot. The velocity route records no compute
		// writer, so its first native recording call - the tag, the only place the native side can
		// see the route - must open the chain with the motion stage skipped. The skip is recorded
		// per slot rather than closed like a real stage: a skipped stage reports exactly zero
		// motion cost, and the evaluation and present marks complete the slot as usual.
		assertTrue(
			evaluation.evaluateFrame(
				scene(),
				jitter(),
				motion(),
				route = MotionVectorRoute.VELOCITY_MRT,
				velocity = ImageBinding(11L, 12L, 124),
			),
		)
		assertEquals(
			listOf("tag", "evaluate"),
			calls.order,
			"the tag must be the velocity frame's first native recording call, preceding the evaluation",
		)

		val tag = nativeSource("internal/sl_dlss.cpp")
			.substringAfter("int32_t tag_sr_resources(")
			.substringBefore("int32_t record_sr_evaluation(")
		// The chain opens only when the route's velocity companion rides the tag, and it closes
		// the motion stage through the explicit skip marker, never through a real stage close:
		// the skipped motion stage must not look like a closed motion pass.
		assertTrue(tag.contains("if (hasVelocity) {"), "the timing chain must open only on the velocity route")
		assertTrue(
			tag.indexOf("if (hasVelocity) {") < tag.indexOf("begin_frame_timing"),
			"the open must be gated on the velocity companion",
		)
		assertTrue(
			tag.indexOf("begin_frame_timing") < tag.indexOf("mark_skipped_motion_timing"),
			"the chain must open before the skipped motion stage is marked",
		)
		assertTrue(
			tag.contains("mark_skipped_motion_timing(commandBuffer)"),
			"the skipped stage must be recorded through the explicit skip marker",
		)
		assertFalse(
			tag.contains("mark_frame_timing("),
			"the tag must not close a real motion stage the velocity route never recorded",
		)
	}

	@Test
	fun `a skipped motion stage reports an explicit zero instead of a stamp span`() {
		// The native timing is not observable through the ABI - the query returns the collected
		// durations, not the slots that produced them - so the stage and value semantics are
		// pinned on the source, like the rest of the native-behaviour gate. What is pinned is
		// the invariant the reviewer required: a skipped motion stage reports true zero, never
		// the TOP-to-BOTTOM span, which for a stage that records no work would only measure
		// earlier command buffers still draining between the two stamps.
		val header = nativeSource("internal/timing.h")
		assertTrue(
			header.contains("bool motionSkipped[kTimingSlotCount]"),
			"the slot must carry the skipped-stage record",
		)

		val timing = nativeSource("internal/timing.cpp")
		val collect =
			timing.substringAfter("void collect_timing(").substringBefore("void write_timing_stamp(")
		val begin =
			timing.substringAfter("void begin_frame_timing(").substringBefore("void mark_frame_timing(")
		val realMark =
			timing.substringAfter("void mark_frame_timing(").substringBefore("void mark_skipped_motion_timing(")
		val skipMark = timing.substringAfter("void mark_skipped_motion_timing(")
		val stampWriter =
			timing.substringAfter("void write_timing_stamp(").substringBefore("} // namespace")

		// Value semantics: collection branches on the slot's skip record - exactly zero for a
		// skipped stage, the stamp delta for a real one - and clears the record with the read.
		assertTrue(
			collect.contains("g_timing.motionMs = g_timing.motionSkipped[slot]"),
			"collection must branch on the skipped-stage record",
		)
		assertTrue(collect.contains("? 0.0f"), "the skipped stage must report exactly zero")
		assertTrue(
			collect.contains("static_cast<float>(stamps[1] - stamps[0])"),
			"a real motion stage must keep its stamp-span duration",
		)
		assertTrue(
			collect.contains("g_timing.motionSkipped[slot] = false;"),
			"the skip record must be cleared with the read",
		)
		assertTrue(
			begin.contains("g_timing.motionSkipped[slot] = false;"),
			"reopening a slot must clear a stale skip record from a dropped frame",
		)

		// Stage semantics: the slot opens at TOP_OF_PIPE and every stage close - real or
		// skipped - stamps at BOTTOM_OF_PIPE, so the evaluate and present spans are unchanged
		// and only the motion duration differs on the skipped route.
		assertTrue(
			begin.contains("VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT"),
			"the slot must open at TOP_OF_PIPE",
		)
		assertTrue(
			stampWriter.contains("VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT"),
			"stage closes must stamp at BOTTOM_OF_PIPE",
		)

		// The skip marker still writes the motion slot's stamp (the evaluate/present chain
		// counts stamps to complete the slot), and it records the skip on the slot it opened;
		// a real stage close never records a skip.
		assertTrue(
			skipMark.contains("write_timing_stamp(commandBuffer, 1);"),
			"the skipped stage must still close the slot's motion stamp",
		)
		assertTrue(
			skipMark.contains("g_timing.motionSkipped[g_timing.recordingSlot] = true;"),
			"the skip marker must record the skip on the slot it opened",
		)
		assertFalse(
			realMark.contains("motionSkipped"),
			"a real stage close must not mark the stage skipped",
		)
	}

	@Test
	fun `the camera-only route keeps opening the timing chain in the compute writer`() {
		val calls = RecordingNative(RENDER_DIMENSIONS)
		val evaluation = evaluation(calls)

		assertTrue(evaluation.evaluateFrame(scene(), jitter(), motion()))
		assertEquals(
			listOf("writeMotion", "tag", "evaluate"),
			calls.order,
			"the camera-only frame must open the timing chain through the compute writer",
		)

		// The writer keeps opening the chain and closing the motion stage, and the evaluate and
		// present marks that complete the slot are unchanged: the repair must not move the
		// camera-only timing path.
		val api = nativeSource("mc_dlss_api.cpp")
		val motion = api.substringAfter("mc_dlss_write_motion(").substringBefore("mc_dlss_evaluate")
		assertTrue(motion.contains("begin_frame_timing"), "the camera-only writer must keep opening the chain")
		assertTrue(motion.contains("mark_frame_timing(recordingBuffer, 1)"))
		val evaluate = api.substringAfter("mc_dlss_evaluate").substringBefore("mc_dlss_present_output")
		assertTrue(evaluate.contains("mark_frame_timing(recordingBuffer, 2)"))
		val present = api.substringAfter("mc_dlss_present_output").substringBefore("mc_dlss_reset")
		assertTrue(present.contains("mark_frame_timing(recordingBuffer, 3)"))
	}

	private fun evaluation(calls: RecordingNative): FrameEvaluation {
		val session = DlssSession(startupConfig())
		val adapter = LifecycleAdapter(session, calls)
		// The adapter stamps the configured render dimensions onto every request from its own
		// initialize, so the frame path has to run through initialize exactly like production
		// (which also moves the session from WAITING_FOR_VULKAN to READY).
		adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data"))
		val context = VulkanContext.fromNativeHandles(
			1L,
			2L,
			3L,
			4L,
			commandBufferSource = { fakeCommandBuffer() },
			commandBufferSink = {},
		)
		return FrameEvaluation(adapter, { context })
	}

	/**
	 * A [VkCommandBuffer] instance whose address() answers without any Vulkan device.
	 *
	 * [VkCommandBuffer]'s constructor dereferences a live device's capabilities, which the
	 * pure-JVM route tests do not have; the frame path only reads `address()`, so an instance
	 * allocated past the constructor is all this fake needs.
	 */
	private fun fakeCommandBuffer(): VkCommandBuffer {
		val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
		unsafeField.isAccessible = true
		val unsafe = unsafeField.get(null) as sun.misc.Unsafe
		return unsafe.allocateInstance(VkCommandBuffer::class.java) as VkCommandBuffer
	}

	private fun worldPhase(
		runtime: RenderRuntime,
		evaluateFrame: (
			RenderTarget,
			RenderTarget,
			DlssJitterOffset,
			DlssFrameMotion,
			MotionVectorRoute,
			GpuTextureView?,
		) -> Boolean,
	) = WorldPhase(
		runtime = runtime,
		present = { _, _ -> },
		onWorldTargetChanged = {},
		evaluateFrame = evaluateFrame,
	)

	private fun scene() = SceneResources(
		color = ImageBinding(201L, 202L, 37),
		depth = ImageBinding(301L, 302L, 126),
	)

	private fun jitter(): DlssJitterOffset = DlssJitter(RENDER_DIMENSIONS, OUTPUT_DIMENSIONS).advance()

	private fun motion() = DlssFrameMotion(Matrix4f(), RENDER_DIMENSIONS.width / 2f, RENDER_DIMENSIONS.height / 2f, 16.6f, true)

	/** Records every per-frame native call so the route gate is assertable off the render thread. */
	private class RecordingNative(
		private val RENDER_DIMENSIONS: DlssDimensions,
	) : NativeApi {
		val writeMotion = mutableListOf<MotionRequest>()
		val tags = mutableListOf<SrTagRequest>()
		val evaluations = mutableListOf<EvaluationRequest>()
		/** The per-frame recording calls in submission order, so the timing-chain seam is assertable. */
		val order = mutableListOf<String>()

		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		): Int = NativeApi.SUCCESS_RESULT

		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int): DlssDimensions =
			DlssDimensions(RENDER_DIMENSIONS.width, RENDER_DIMENSIONS.height)

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

		override fun writeMotion(request: MotionRequest): Int {
			writeMotion += request
			order += "writeMotion"
			return NativeApi.SUCCESS_RESULT
		}

		override fun tagSrResources(request: SrTagRequest): Int {
			tags += request
			order += "tag"
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

	/** Render target with a fake view over a fake texture, so the handoff is testable off the render thread. */
}
