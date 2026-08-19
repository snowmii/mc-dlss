package me.snowmii.dlss.mrt

import me.snowmii.dlss.render.DlssFrameMotion
import me.snowmii.dlss.render.DlssJitter
import me.snowmii.dlss.render.DlssJitterOffset
import me.snowmii.dlss.render.FrameEvaluation
import me.snowmii.dlss.render.SceneResources
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.session.SRMode
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.EvaluationImages
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.streamline.FillVelocityRequest
import me.snowmii.streamline.FrameTimings
import me.snowmii.streamline.ImageBinding
import me.snowmii.streamline.MotionRequest
import me.snowmii.streamline.PresentTarget
import me.snowmii.streamline.SrTagRequest
import me.snowmii.streamline.StreamlineSession
import me.snowmii.streamline.StreamlineSessionTestDouble
import me.snowmii.streamline.VulkanContext
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.vulkan.VkCommandBuffer
import java.nio.file.Path

/**
 * The sentinel-fill gate: the velocity-MRT route records one fill before tagging and
 * evaluation, while camera-only routing keeps its compute writer. These tests exercise frame
 * routing against [StreamlineSessionTestDouble], including reset propagation and fill order.
 */
class MotionVectorSentinelFillTest {
	@Test
	fun `the velocity route fills the native motion image from the sampled companion before tagging`() {
		val calls = RecordingNativeApi(RENDER_DIMENSIONS)
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
		val fill = calls.fills.single()
		assertEquals(velocity, fill.velocity, "the fill must sample the scene velocity companion")
		assertEquals(scene().depth, fill.depth, "the fill must read the scene depth image")
		val expectedReprojection = FloatArray(16).also { motion().reprojection.get(it) }
		assertTrue(
			expectedReprojection.contentEquals(fill.reprojection),
			"the fill must carry the jitter-stripped reprojection",
		)
		assertFalse(fill.reset, "a continuous frame must not carry the reset flag")
		assertEquals(
			listOf("fill", "tag", "evaluate"),
			calls.order,
			"the fill must precede the tag in the frame's recording",
		)
		assertTrue(calls.writeMotion.isEmpty(), "the velocity route must not record the compute camera-motion writer")
		// The tag request carries only the engine colour and depth; McDlssTagInfo does not
		// carry the engine's velocity image. The native side tags the module's motion image
		// as the motion source, so the fill destination never crosses the ABI back in.
		assertEquals(scene().color, calls.tags.single().color)
		assertEquals(scene().depth, calls.tags.single().depth)
		assertEquals(1, calls.evaluations.size, "the frame must still evaluate")
	}

	@Test
	fun `the fill carries the reset flag of the frame's motion`() {
		val calls = RecordingNativeApi(RENDER_DIMENSIONS)
		val evaluation = evaluation(calls)

		// A reset frame has no valid predecessor: the native fill must write the invalid
		// sentinel everywhere rather than reconstruct camera motion, and the flag that decides
		// that rides the fill request.
		assertTrue(
			evaluation.evaluateFrame(
				scene(),
				jitter(),
				motion(reset = true),
				route = MotionVectorRoute.VELOCITY_MRT,
				velocity = ImageBinding(11L, 12L, 124),
			),
		)
		assertTrue(calls.fills.single().reset, "a reset frame must carry the reset flag to the fill")
	}

	@Test
	fun `the camera-only route records no fill`() {
		val calls = RecordingNativeApi(RENDER_DIMENSIONS)
		val evaluation = evaluation(calls)

		assertTrue(evaluation.evaluateFrame(scene(), jitter(), motion()))
		assertTrue(calls.fills.isEmpty(), "the camera-only route must keep the compute writer and no fill")
		assertEquals(1, calls.writeMotion.size)
	}

	@Test
	fun `a velocity route without a companion records nothing`() {
		val calls = RecordingNativeApi(RENDER_DIMENSIONS)
		val evaluation = evaluation(calls)

		assertFalse(evaluation.evaluateFrame(scene(), jitter(), motion(), route = MotionVectorRoute.VELOCITY_MRT))
		assertTrue(calls.fills.isEmpty())
		assertTrue(calls.tags.isEmpty())
		assertTrue(calls.evaluations.isEmpty())
	}

	@Test
	fun `the adapter stamps the configured render dimensions onto the fill`() {
		val calls = RecordingNativeApi(RENDER_DIMENSIONS)
		val evaluation = evaluation(calls)

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
			RENDER_DIMENSIONS,
			calls.fills.single().renderDimensions,
			"the adapter must stamp the configured render size onto the fill, like every recording call",
		)
	}

	private fun evaluation(calls: RecordingNativeApi): FrameEvaluation {
		val session = DlssSession(startupConfig())
		val adapter = LifecycleAdapter(session, calls)
		adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data"))
		val context = VulkanContext.fromNativeHandles(
			1L,
			2L,
			3L,
			4L,
			0,
			0,
			0,
			0,
			{ fakeCommandBuffer() },
			{ },
		)
		return FrameEvaluation(adapter, { context })
	}

	/**
	 * A [VkCommandBuffer] whose `address()` answers without a Vulkan device. The constructor
	 * dereferences a live device; the frame path only reads `address()`.
	 */
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

	private fun jitter(): DlssJitterOffset = DlssJitter(RENDER_DIMENSIONS, OUTPUT_DIMENSIONS).advance()

	private fun motion(reset: Boolean = false) =
		DlssFrameMotion(Matrix4f(), RENDER_DIMENSIONS.width / 2f, RENDER_DIMENSIONS.height / 2f, 16.6f, reset)

	private class RecordingNativeApi(
		private val renderDimensions: Dimensions,
	) : StreamlineSessionTestDouble() {
		val fills = mutableListOf<FillVelocityRequest>()
		val writeMotion = mutableListOf<MotionRequest>()
		val tags = mutableListOf<SrTagRequest>()
		val evaluations = mutableListOf<EvaluationRequest>()
		/** The per-frame recording calls in submission order, so the fill-before-tag seam is assertable. */
		val order = mutableListOf<String>()

		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		): Int = StreamlineSession.SUCCESS_RESULT

		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int): Dimensions =
			Dimensions(renderDimensions.width, renderDimensions.height)

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

		override fun fillVelocity(request: FillVelocityRequest): Int {
			fills += request
			order += "fill"
			return StreamlineSession.SUCCESS_RESULT
		}

		override fun writeMotion(request: MotionRequest): Int {
			writeMotion += request
			order += "writeMotion"
			return StreamlineSession.SUCCESS_RESULT
		}

		override fun tagSrResources(request: SrTagRequest): Int {
			tags += request
			order += "tag"
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

	private companion object {
		val RENDER_DIMENSIONS = Dimensions(1280, 720)
		val OUTPUT_DIMENSIONS = Dimensions(2560, 1440)
	}

	private fun startupConfig() = DlssStartupConfig(
		enabled = true,
		qualityMode = SRMode.PERFORMANCE,
		outputDimensions = OUTPUT_DIMENSIONS,
		sdkPath = null,
		nativeLibraryPath = null,
		dataPath = null,
		warnings = emptyList(),
	)
}
