package me.snowmii.dlss.mrt

import java.nio.file.Path
import kotlin.io.path.readText
import me.snowmii.dlss.nativeSource
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.bridge.DlssEvaluationImages
import me.snowmii.dlss.bridge.DlssFrameTimings
import me.snowmii.dlss.bridge.EvaluationRequest
import me.snowmii.dlss.bridge.FillVelocityRequest
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
 * The M-6 route gate: the velocity-MRT frame merges the scene's RG16_FLOAT velocity companion
 * into the native motion image through the sentinel fill, and tags only that image, while the
 * camera-only frame preserves the existing compute writer and the native motion image path
 * byte for byte.
 *
 * On VELOCITY_MRT the frame's motion source is the native motion image - the sole Streamline
 * motion source: [FrameEvaluation] records the post-scene fill with the velocity view before
 * the tag, and direct companion tagging is retired, so the tag request is route-independent.
 * This test pins the activation: the route decided by [WorldPhase] is handed into the
 * evaluation with the velocity view, [FrameEvaluation] records the fill on the velocity route
 * and the compute writer on the camera-only route, and the native tag call always selects the
 * module's motion image as the motion-vector buffer.
 *
 * The whole test is pure JVM: the frame path runs against a recording [NativeApi] fake and the
 * native behaviour is pinned by source text, exactly like the other M-4/M-5 route tests. The
 * live Streamline tests stay on the camera-only default, so nothing here changes what they
 * exercise.
 */
class MotionVectorRouteTest {
	private val mainTarget = fakeMainTarget()


	@Test
	fun `the velocity route fills before tagging and tags only the native motion image`() {
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
		assertEquals(velocity, calls.fills.single().velocity, "the fill must sample the frame's velocity companion")
		assertTrue(calls.writeMotion.isEmpty(), "the velocity route must not record the compute camera-motion writer")
		// The tag is route-independent: it carries the engine colour and depth only, and the
		// native side always tags the module's motion image as the motion source.
		assertEquals(scene().color, calls.tags.single().color)
		assertEquals(scene().depth, calls.tags.single().depth)
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
		assertTrue(calls.fills.isEmpty(), "the camera-only route must record no fill")
		assertEquals(scene().color, calls.tags.single().color)
		assertEquals(scene().depth, calls.tags.single().depth)
		assertEquals(1, calls.evaluations.size)
	}

	@Test
	fun `a stray velocity on the camera-only route cannot trigger the fill`() {
		val calls = RecordingNative(RENDER_DIMENSIONS)
		val evaluation = evaluation(calls)

		// WorldPhase never produces this shape, but a caller that does must not move the
		// camera-only route onto a fill the compute writer never needed: the fill belongs to
		// the velocity route alone.
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
		assertTrue(calls.fills.isEmpty(), "the camera-only route must never record the fill")
	}

	@Test
	fun `the velocity route without a velocity companion is not evaluated`() {
		val calls = RecordingNative(RENDER_DIMENSIONS)
		val evaluation = evaluation(calls)

		// A velocity-route frame with no motion source at all must skip rather than hand DLSS
		// an image the fill never merged into.
		assertFalse(evaluation.evaluateFrame(scene(), jitter(), motion(), route = MotionVectorRoute.VELOCITY_MRT))
		assertTrue(calls.fills.isEmpty())
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
	fun `the tag ABI carries no velocity and the native tag call always selects the module motion image`() {
		val header = nativeSource("mc_dlss.h")
		val tagStruct = header.substringAfter("typedef struct McDlssTagInfo {")
			.substringBefore("} McDlssTagInfo;")
		assertFalse(
			tagStruct.contains("velocity"),
			"McDlssTagInfo must no longer carry the engine's velocity image - direct companion tagging is retired",
		)

		val java = Path.of("")
			.toAbsolutePath()
			.resolve("src/main/java/me/snowmii/dlss/bridge/Native.java")
			.readText()
		assertTrue(java.contains("IMAGE_LAYOUT.withName(\"depth\")"), "the FFM tag layout must mirror the tag struct")
		assertFalse(java.contains("TAG_VELOCITY_VIEW"), "the FFM tag layout must carry no velocity field")

		val sl = nativeSource("internal/sl_dlss.cpp")
		// The motion source is unconditional: the module's motion image is tagged as the
		// motion-vector buffer on every route, and no companion resource is ever described.
		assertTrue(sl.contains("kBufferTypeMotionVectors"))
		assertFalse(sl.contains("velocityResource"), "the tag must not describe a companion resource")
		assertFalse(sl.contains("hasVelocity"), "the tag must not branch on a companion field")
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
		val fills = mutableListOf<FillVelocityRequest>()
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

		override fun fillVelocity(request: FillVelocityRequest): Int {
			fills += request
			order += "fill"
			return NativeApi.SUCCESS_RESULT
		}

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
