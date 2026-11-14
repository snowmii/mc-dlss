package me.snowmii.dlss.render.mrt

import com.mojang.blaze3d.pipeline.RenderTarget
import me.snowmii.dlss.render.DlssCameraSample
import me.snowmii.dlss.render.DlssFrameMotion
import me.snowmii.dlss.render.DlssJitter
import me.snowmii.dlss.render.DlssJitterOffset
import me.snowmii.dlss.render.FrameEvaluation
import me.snowmii.dlss.render.RenderRuntime
import me.snowmii.dlss.render.SceneResources
import me.snowmii.dlss.render.WorldPhase
import me.snowmii.dlss.DlssSession
import me.snowmii.dlss.streamline.LifecycleAdapter
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.EvaluationImages
import me.snowmii.streamline.EvaluationRequest
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.vulkan.VkCommandBuffer
import java.nio.file.Path

class MotionVectorRouteTest {
	private val mainTarget = fakeMainTarget()

	@Test
	fun `the camera-only route keeps the compute writer and the native motion image path`() {
		val calls = RecordingNativeApi(RENDER_DIMENSIONS)
		val evaluation = evaluation(calls)

		assertTrue(evaluation.evaluateFrame(scene(), jitter(), motion()))
		assertEquals(1, calls.writeMotion.size, "the camera-only route must keep the compute writer")
		assertEquals(scene().color, calls.tags.single().color)
		assertEquals(scene().depth, calls.tags.single().depth)
		assertEquals(1, calls.evaluations.size)
	}

	@Test
	fun `the world phase hands scene targets and camera sample into the evaluation`() {
		val runtime = velocityRuntime()
		var evaluated = false
		val phase = worldPhase(runtime) { rendered, destination, jitter, motion, camera ->
			assertNotNull(rendered)
			assertEquals(mainTarget, destination)
			assertNotNull(jitter)
			assertNotNull(motion)
			assertNotNull(camera)
			evaluated = true
			true
		}

		phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = cameraSample())
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		phase.end()

		assertTrue(evaluated, "a frame reaches the evaluation")
	}

	@Test
	fun `the camera-only route keeps opening the timing chain in the compute writer`() {
		val calls = RecordingNativeApi(RENDER_DIMENSIONS)
		val evaluation = evaluation(calls)

		assertTrue(evaluation.evaluateFrame(scene(), jitter(), motion()))
		assertEquals(
			listOf("writeMotion", "tag", "evaluate"),
			calls.order,
			"the camera-only frame must open the timing chain through the compute writer",
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
			DlssCameraSample?,
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

	private class RecordingNativeApi(
		private val renderDimensions: Dimensions,
	) : StreamlineSessionTestDouble() {
		val writeMotion = mutableListOf<MotionRequest>()
		val tags = mutableListOf<SrTagRequest>()
		val evaluations = mutableListOf<EvaluationRequest>()
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
}
