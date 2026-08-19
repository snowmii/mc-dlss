package me.snowmii.dlss.fg

import java.nio.file.Path
import me.snowmii.dlss.mrt.MotionVectorRoute
import me.snowmii.dlss.render.DlssCameraSample
import me.snowmii.dlss.render.DlssFrameMotion
import me.snowmii.dlss.render.DlssJitter
import me.snowmii.dlss.render.FgFrameInputs
import me.snowmii.dlss.render.FrameEvaluation
import me.snowmii.dlss.render.SceneResources
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.session.SRMode
import me.snowmii.streamline.CameraConstants
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.EvaluationImages
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.streamline.FgTagRequest
import me.snowmii.streamline.ImageBinding
import me.snowmii.streamline.MotionRequest
import me.snowmii.streamline.PresentTarget
import me.snowmii.streamline.SrTagRequest
import me.snowmii.streamline.StreamlineSession
import me.snowmii.streamline.StreamlineSessionTestDouble
import me.snowmii.streamline.VulkanContext
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.vulkan.VkCommandBuffer

/**
 * Composed frames record FG before SR, retain one shared frame token, and pass camera
 * constants through the evaluation request. Native viewport-orientation behavior belongs to
 * Streamline's live boundary tests.
 */
class FgViewportSplitTest {

	@Test
	fun `composed frame sends one camera to SR and records FG around evaluation`() {
		val native = RecordingNativeApi()
		val policy = FgSurfacePolicy().also { it.setFrameGenerationActive(true) }
		val evaluation = evaluation(native, policy)
		val camera = DlssCameraSample(
			projection = Matrix4f().perspective(1.2f, 16f / 9f, 0.05f, 1000f, true),
			viewRotation = Matrix4f().rotationY(0.4f),
			cameraX = 12.0,
			cameraY = 64.0,
			cameraZ = -48.0,
		)

		assertTrue(
			evaluation.evaluateFrame(
				scene(),
				DlssJitter(RENDER_DIMENSIONS, OUTPUT_DIMENSIONS).advance(),
				DlssFrameMotion(Matrix4f(), 640f, 360f, 16.6f, false),
				DESTINATION,
				MotionVectorRoute.CAMERA_ONLY,
				camera = camera,
			),
		)

		assertEquals(
			listOf("writeMotion", "configureFg", "fgTag", "srTag", "evaluate", "present", "fgTag", "handoff"),
			native.order,
			"FG must tag before SR, retag after output copy, then hand off",
		)
		assertEquals(2, native.fgTags.size)
		assertEquals(native.fgTags.first(), native.fgTags.last())
		assertEquals(native.fgTags.first().commandBuffer, native.evaluateRequests.single().commandBuffer)
		val constants = native.evaluateRequests.single().camera
		assertNotNull(constants)
		assertTrue(constants!!.viewToClip.contentEquals(CameraConstants.rowMajorOf(camera.projection)))
		assertTrue(constants.pos.contentEquals(floatArrayOf(12f, 64f, -48f)))
	}

	private fun evaluation(native: RecordingNativeApi, policy: FgSurfacePolicy): FrameEvaluation {
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
		adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data"))
		val context = VulkanContext.fromNativeHandles(
			1L, 2L, 3L, 4L, 0, 0, 0, 0,
			{ fakeCommandBuffer() },
			{},
		)
		return FrameEvaluation(
			adapter,
			{ context },
			frameGeneration = policy,
			fgInputs = { fgInputs() },
		)
	}

	private fun scene() = SceneResources(
		ImageBinding(201L, 202L, 37),
		ImageBinding(301L, 302L, 126),
	)

	private fun fgInputs() = FgFrameInputs(
		ImageBinding(601L, 602L, 37),
		ImageBinding(701L, 702L, 37),
	)

	private fun fakeCommandBuffer(): VkCommandBuffer {
		val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
		field.isAccessible = true
		return (field.get(null) as sun.misc.Unsafe).allocateInstance(VkCommandBuffer::class.java) as VkCommandBuffer
	}

	private class RecordingNativeApi : StreamlineSessionTestDouble() {
		val order = mutableListOf<String>()
		val fgTags = mutableListOf<FgTagRequest>()
		val evaluateRequests = mutableListOf<EvaluationRequest>()

		override fun initialize(vkInstance: Long, vkPhysicalDevice: Long, vkDevice: Long, sdkPath: Path, dataPath: Path) = StreamlineSession.SUCCESS_RESULT
		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int) = RENDER_DIMENSIONS
		override fun configureSuperResolution(outputWidth: Int, outputHeight: Int, renderWidth: Int, renderHeight: Int, qualityMode: Int, renderPreset: Int) = StreamlineSession.SUCCESS_RESULT
		override fun acquireImages() = EvaluationImages(ImageBinding(401L, 402L, 124), ImageBinding(501L, 502L, 37))
		override fun releaseImages() = StreamlineSession.SUCCESS_RESULT
		override fun waitDeviceIdle() = StreamlineSession.SUCCESS_RESULT
		override fun configureFg(numBackBuffers: Int): Int { order += "configureFg"; return StreamlineSession.SUCCESS_RESULT }
		override fun tagFrameGenerationResources(request: FgTagRequest): Int { fgTags += request; order += "fgTag"; return StreamlineSession.SUCCESS_RESULT }
		override fun tagSrResources(request: SrTagRequest): Int { order += "srTag"; return StreamlineSession.SUCCESS_RESULT }
		override fun writeMotion(request: MotionRequest): Int { order += "writeMotion"; return StreamlineSession.SUCCESS_RESULT }
		override fun evaluateSuperResolution(request: EvaluationRequest): Int { evaluateRequests += request; order += "evaluate"; return StreamlineSession.SUCCESS_RESULT }
		override fun presentOutput(target: PresentTarget): Int { order += "present"; return StreamlineSession.SUCCESS_RESULT }
		override fun recordPresentHandoff(): Int { order += "handoff"; return StreamlineSession.SUCCESS_RESULT }
	}

	private companion object {
		val RENDER_DIMENSIONS = Dimensions(1280, 720)
		val OUTPUT_DIMENSIONS = Dimensions(2560, 1440)
		const val DESTINATION = 900L
	}
}
