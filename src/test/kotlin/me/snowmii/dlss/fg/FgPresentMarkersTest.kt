package me.snowmii.dlss.fg

import java.nio.file.Path
import me.snowmii.dlss.mrt.MotionVectorRoute
import me.snowmii.dlss.render.DlssFrameMotion
import me.snowmii.dlss.render.DlssJitter
import me.snowmii.dlss.render.FgFrameInputs
import me.snowmii.dlss.render.FrameEvaluation
import me.snowmii.dlss.render.SceneResources
import me.snowmii.streamline.VulkanContext
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.session.SRMode
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.EvaluationImages
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.streamline.FgTagRequest
import me.snowmii.streamline.ImageBinding
import me.snowmii.streamline.MotionRequest
import me.snowmii.streamline.NativeApi
import me.snowmii.streamline.NativeApiTestDouble
import me.snowmii.streamline.PresentTarget
import me.snowmii.streamline.SrTagRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.joml.Matrix4f
import org.junit.jupiter.api.Test
import org.lwjgl.vulkan.VkCommandBuffer

/**
 * Checks mod-owned FG present wiring. Streamline owns marker ABI and marker ordering; this test
 * only proves that one composed mod frame reaches present handoff after its final FG tag.
 */
class FgPresentMarkersTest {

	@Test
	fun `FG frame hands off after final FG tag on same command buffer`() {
		val native = RecordingNativeApi()
		val policy = FgSurfacePolicy().also { it.setFrameGenerationActive(true) }
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
		val evaluation = FrameEvaluation(
			adapter,
			{
				VulkanContext.fromNativeHandles(
					1L, 2L, 3L, 4L, 0, 0, 0, 0,
					{ fakeCommandBuffer() },
					{},
				)
			},
			frameGeneration = policy,
			fgInputs = { fgInputs() },
		)

		assertTrue(
			evaluation.evaluateFrame(
				scene = scene(),
				jitter = DlssJitter(RENDER_DIMENSIONS, OUTPUT_DIMENSIONS).advance(),
				motion = DlssFrameMotion(Matrix4f(), 640f, 360f, 16.6f, false),
				destinationImage = DESTINATION,
				route = MotionVectorRoute.CAMERA_ONLY,
			),
		)
		assertEquals(
			listOf("writeMotion", "configureFg", "fgTag", "srTag", "evaluate", "present", "fgTag", "handoff"),
			native.order,
			"mod must hand off only after output present and final FG tag",
		)
		assertEquals(2, native.fgTags.size)
		assertEquals(native.fgTags[0], native.fgTags[1])
		assertEquals(native.fgTags[0].commandBuffer, native.evaluateRequests.single().commandBuffer)
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

	private class RecordingNativeApi : NativeApiTestDouble() {
		val order = mutableListOf<String>()
		val fgTags = mutableListOf<FgTagRequest>()
		val evaluateRequests = mutableListOf<EvaluationRequest>()

		override fun initialize(vkInstance: Long, vkPhysicalDevice: Long, vkDevice: Long, sdkPath: Path, dataPath: Path) = NativeApi.SUCCESS_RESULT
		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int) = RENDER_DIMENSIONS
		override fun configureSuperResolution(outputWidth: Int, outputHeight: Int, renderWidth: Int, renderHeight: Int, qualityMode: Int, renderPreset: Int) = NativeApi.SUCCESS_RESULT
		override fun acquireImages() = EvaluationImages(ImageBinding(401L, 402L, 124), ImageBinding(501L, 502L, 37))
		override fun writeMotion(request: MotionRequest): Int { order += "writeMotion"; return NativeApi.SUCCESS_RESULT }
		override fun configureFg(numBackBuffers: Int): Int { order += "configureFg"; return NativeApi.SUCCESS_RESULT }
		override fun tagFrameGenerationResources(request: FgTagRequest): Int { fgTags += request; order += "fgTag"; return NativeApi.SUCCESS_RESULT }
		override fun tagSrResources(request: SrTagRequest): Int { order += "srTag"; return NativeApi.SUCCESS_RESULT }
		override fun evaluateSuperResolution(request: EvaluationRequest): Int { evaluateRequests += request; order += "evaluate"; return NativeApi.SUCCESS_RESULT }
		override fun presentOutput(target: PresentTarget): Int { order += "present"; return NativeApi.SUCCESS_RESULT }
		override fun recordPresentHandoff(): Int { order += "handoff"; return NativeApi.SUCCESS_RESULT }
	}

	private companion object {
		val RENDER_DIMENSIONS = Dimensions(1280, 720)
		val OUTPUT_DIMENSIONS = Dimensions(2560, 1440)
		const val DESTINATION = 900L
	}
}
