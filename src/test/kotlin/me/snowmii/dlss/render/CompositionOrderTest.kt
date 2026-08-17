package me.snowmii.dlss.render

import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.session.SRMode
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.EvaluationImages
import me.snowmii.streamline.EvaluationRequest
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.vulkan.VkCommandBuffer
import java.nio.file.Path

/**
 * Verifies mod-owned composition order: evaluation records before the output copy, and both use
 * one engine-owned command buffer. Streamline's native copy behavior belongs to live tests.
 */
class CompositionOrderTest {

	@Test
	fun `evaluation is recorded before output copy on engine command buffer`() {
		val native = RecordingNativeApi()
		val session = DlssSession(
			DlssStartupConfig(
				enabled = true,
				qualityMode = SRMode.QUALITY,
				outputDimensions = OUTPUT,
				sdkPath = null,
				nativeLibraryPath = null,
				dataPath = null,
				warnings = emptyList(),
			),
		)
		val adapter = LifecycleAdapter(session, native)
		adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data"))
		var submissions = 0
		val context = VulkanContext.fromNativeHandles(
			1L, 2L, 3L, 4L, 0, 0, 0, 0,
			{ fakeCommandBuffer() },
			{ submissions++ },
		)
		val evaluation = FrameEvaluation(adapter, { context })

		assertTrue(
			evaluation.evaluateFrame(
				SceneResources(ImageBinding(201L, 202L, 37), ImageBinding(301L, 302L, 126)),
				DlssJitter(RENDER, OUTPUT).advance(),
				DlssFrameMotion(Matrix4f(), RENDER.width / 2f, RENDER.height / 2f, 16.6f, true),
				DESTINATION,
			),
			"frame must compose through mod-owned seams",
		)

		assertEquals(listOf("writeMotion", "srTag", "evaluate", "present"), native.order)
		assertEquals(1, native.commandBuffers.distinct().size)
		assertEquals(native.commandBuffers.first(), native.presentTargets.single().commandBuffer)
		assertEquals(DESTINATION, native.presentTargets.single().image)
		assertEquals(1, submissions)
		evaluation.close()
	}

	private fun fakeCommandBuffer(): VkCommandBuffer {
		val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
		field.isAccessible = true
		val unsafe = field.get(null) as sun.misc.Unsafe
		return unsafe.allocateInstance(VkCommandBuffer::class.java) as VkCommandBuffer
	}

	private class RecordingNativeApi : StreamlineSessionTestDouble() {
		val order = mutableListOf<String>()
		val commandBuffers = mutableListOf<Long>()
		val presentTargets = mutableListOf<PresentTarget>()

		override fun initialize(vkInstance: Long, vkPhysicalDevice: Long, vkDevice: Long, sdkPath: Path, dataPath: Path) =
			StreamlineSession.SUCCESS_RESULT
		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int) = RENDER
		override fun configureSuperResolution(outputWidth: Int, outputHeight: Int, renderWidth: Int, renderHeight: Int, qualityMode: Int, renderPreset: Int) =
			StreamlineSession.SUCCESS_RESULT
		override fun acquireImages() = EvaluationImages(ImageBinding(401L, 402L, 124), ImageBinding(501L, 502L, 37))
		override fun releaseImages() = StreamlineSession.SUCCESS_RESULT
		override fun waitDeviceIdle() = StreamlineSession.SUCCESS_RESULT
		override fun frameTimings(): FrameTimings? = null
		override fun writeMotion(request: MotionRequest): Int { commandBuffers += request.commandBuffer; order += "writeMotion"; return StreamlineSession.SUCCESS_RESULT }
		override fun tagSrResources(request: SrTagRequest): Int { commandBuffers += request.commandBuffer; order += "srTag"; return StreamlineSession.SUCCESS_RESULT }
		override fun evaluateSuperResolution(request: EvaluationRequest): Int { commandBuffers += request.commandBuffer; order += "evaluate"; return StreamlineSession.SUCCESS_RESULT }
		override fun presentOutput(target: PresentTarget): Int { commandBuffers += target.commandBuffer; presentTargets += target; order += "present"; return StreamlineSession.SUCCESS_RESULT }
	}

	private companion object {
		val RENDER = Dimensions(1280, 720)
		val OUTPUT = Dimensions(2560, 1440)
		const val DESTINATION = 900L
	}
}
