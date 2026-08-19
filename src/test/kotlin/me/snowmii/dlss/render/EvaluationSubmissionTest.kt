package me.snowmii.dlss.render

import me.snowmii.dlss.DlssSession
import me.snowmii.dlss.DlssStartupConfig
import me.snowmii.dlss.streamline.LifecycleAdapter
import me.snowmii.dlss.SRMode
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.lwjgl.vulkan.VkCommandBuffer
import java.nio.file.Path

class EvaluationSubmissionTest {
	private val output = Dimensions(1280, 720)

	@Test
	fun `one frame records motion and evaluation in order on the engine's own submission`(@TempDir dataPath: Path) {
		val native = RecordingNative()
		val session = DlssSession(
			DlssStartupConfig(
				enabled = true,
				qualityMode = SRMode.QUALITY,
				outputDimensions = output,
				sdkPath = dataPath,
				nativeLibraryPath = dataPath,
				dataPath = dataPath,
				warnings = emptyList(),
			),
		)
		val adapter = LifecycleAdapter(session, native)
		val render = adapter.initialize(1L, 2L, 3L, dataPath, dataPath)
		assertNotNull(render, session.failure?.diagnostic())
		val renderSize = checkNotNull(render)

		val taken = mutableListOf<VkCommandBuffer>()
		val submitted = mutableListOf<VkCommandBuffer>()
		val context = VulkanContext.fromNativeHandles(1L, 2L, 3L, 4L, 0, 0, 0, 0,
			{ fakeCommandBuffer().also(taken::add) },
			{ submitted += it },
		)
		val evaluation = FrameEvaluation(adapter, context = { context })
		assertTrue(
			evaluation.evaluateFrame(
				SceneResources(ImageBinding(201L, 202L, 37), ImageBinding(301L, 302L, 126)),
				DlssJitter(renderSize, output).advance(),
				DlssFrameMotion(Matrix4f(), renderSize.width / 2f, renderSize.height / 2f, 16.6f, true),
				900L,
			),
			session.failure?.diagnostic(),
		)
		assertEquals(listOf("writeMotion", "srTag", "evaluate", "present"), native.order)
		assertEquals(1, taken.size)
		assertEquals(taken.single(), submitted.single())
		assertEquals(List(4) { taken.single().address() }, native.commandBuffers)
		evaluation.close()
	}

	private fun fakeCommandBuffer(): VkCommandBuffer {
		val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
		field.isAccessible = true
		val unsafe = field.get(null) as sun.misc.Unsafe
		return unsafe.allocateInstance(VkCommandBuffer::class.java) as VkCommandBuffer
	}

	private class RecordingNative : StreamlineSessionTestDouble() {
		val order = mutableListOf<String>()
		val commandBuffers = mutableListOf<Long>()

		override fun initialize(vkInstance: Long, vkPhysicalDevice: Long, vkDevice: Long, sdkPath: Path, dataPath: Path) = StreamlineSession.SUCCESS_RESULT
		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int) = Dimensions(640, 360)
		override fun configureSuperResolution(outputWidth: Int, outputHeight: Int, renderWidth: Int, renderHeight: Int, qualityMode: Int, renderPreset: Int) = StreamlineSession.SUCCESS_RESULT
		override fun acquireImages() = EvaluationImages(ImageBinding(401L, 402L, 124), ImageBinding(501L, 502L, 37))
		override fun writeMotion(request: MotionRequest): Int { commandBuffers += request.commandBuffer; order += "writeMotion"; return StreamlineSession.SUCCESS_RESULT }
		override fun tagSrResources(request: SrTagRequest): Int { commandBuffers += request.commandBuffer; order += "srTag"; return StreamlineSession.SUCCESS_RESULT }
		override fun evaluateSuperResolution(request: EvaluationRequest): Int { commandBuffers += request.commandBuffer; order += "evaluate"; return StreamlineSession.SUCCESS_RESULT }
		override fun presentOutput(target: PresentTarget): Int { commandBuffers += target.commandBuffer; order += "present"; return StreamlineSession.SUCCESS_RESULT }
	}

	@Test
	fun `a session that never reached ready records nothing and takes no command buffer`(
		@TempDir dataPath: Path,
	) {
		val session = DlssSession(
			DlssStartupConfig(
				enabled = false,
				qualityMode = SRMode.QUALITY,
				outputDimensions = output,
				sdkPath = dataPath,
				nativeLibraryPath = dataPath,
				dataPath = dataPath,
				warnings = emptyList(),
			),
		)
		var taken = 0
		var submitted = 0
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
				taken++
				throw AssertionError("no command buffer may be taken without a ready session")
			},
			{ submitted++ },
		)
		val evaluation = FrameEvaluation(LifecycleAdapter(session, UnusableNative()), { context })

		assertFalse(
			evaluation.evaluateFrame(
				SceneResources(
					color = ImageBinding(1L, 2L, 37),
					depth = ImageBinding(3L, 4L, 126),
				),
				DlssJitterOffset(0, 0f, 0f, Dimensions(640, 360)),
				DlssFrameMotion(Matrix4f(), 320f, 180f, 0f, true),
			),
		)
		assertEquals(0, taken)
		assertEquals(0, submitted)
	}

	/** Fails every call: a session that never reached READY must not reach the native side at all. */
	private class UnusableNative : StreamlineSessionTestDouble() {
		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		): Int = unreachable()

		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int): Dimensions =
			unreachable()

		override fun configureSuperResolution(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		): Int = unreachable()

		override fun acquireImages(): EvaluationImages = unreachable()

		override fun releaseImages(): Int = unreachable()

		override fun waitDeviceIdle(): Int = StreamlineSession.SUCCESS_RESULT

		override fun frameTimings(): FrameTimings? = null

		override fun writeMotion(request: MotionRequest): Int = unreachable()

		override fun presentOutput(target: PresentTarget): Int = unreachable()

		@Suppress("LongParameterList")
		override fun evaluateSuperResolution(request: EvaluationRequest): Int = unreachable()

		private fun unreachable(): Nothing = throw AssertionError("native must not be reached")
	}

	@Test
	fun `camera motion keeps caller data and stamps configured render dimensions`() {
		val native = WritingNative()
		val adapter = LifecycleAdapter(
			DlssSession(
				DlssStartupConfig(
					enabled = true,
					qualityMode = SRMode.QUALITY,
					outputDimensions = Dimensions(2560, 1440),
					sdkPath = Path.of("sdk"),
					nativeLibraryPath = null,
					dataPath = Path.of("data"),
					warnings = emptyList(),
				),
			),
			native,
		)
		assertTrue(adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")) != null)
		val request = MotionRequest(77L, ImageBinding(11L, 12L, 13), FloatArray(16) { it.toFloat() }, Dimensions(1, 1))

		assertTrue(adapter.writeMotion(request))
		assertEquals(
			MotionRequest(77L, ImageBinding(11L, 12L, 13), FloatArray(16) { it.toFloat() }, Dimensions(1280, 720)),
			native.lastMotion,
		)
	}

	@Test
	fun `evaluation barriers keep mod barriers on caller buffer across frames`(@TempDir dataPath: Path) {
		val native = BarrierNative()
		val session = DlssSession(
			DlssStartupConfig(
				enabled = true,
				qualityMode = SRMode.QUALITY,
				outputDimensions = Dimensions(2560, 1440),
				sdkPath = dataPath,
				nativeLibraryPath = dataPath,
				dataPath = dataPath,
				warnings = emptyList(),
			),
		)
		val adapter = LifecycleAdapter(session, native)
		assertEquals(Dimensions(1280, 720), adapter.initialize(1L, 2L, 3L, dataPath, dataPath))

		val color = ImageBinding(0x102L, 0x101L, 37)
		val depth = ImageBinding(0x202L, 0x201L, 126)
		for (commandBuffer in longArrayOf(11L, 12L)) {
			assertTrue(adapter.tagSrResources(SrTagRequest(commandBuffer, color, depth)))
			assertTrue(
				adapter.evaluate(
					EvaluationRequest.builder()
						.commandBuffer(commandBuffer)
						.color(color)
						.depth(depth)
						.frameTimeMilliseconds(16.6f)
						.resetHistory(true)
						.build(),
				),
			)
		}
		assertEquals(listOf(11L, 12L), native.taggedBuffers)
		assertEquals(listOf(11L, 12L), native.evaluated.map(EvaluationRequest::commandBuffer))
		assertEquals(listOf(color, color), native.evaluated.map(EvaluationRequest::color))
		assertEquals(listOf(depth, depth), native.evaluated.map(EvaluationRequest::depth))
	}

	@Test
	fun `ready session reuses and rebuilds evaluation images`() {
		val native = ImageNative()
		val session = DlssSession(
			DlssStartupConfig(
				enabled = true,
				qualityMode = SRMode.QUALITY,
				outputDimensions = Dimensions(2560, 1440),
				sdkPath = Path.of("sdk"),
				nativeLibraryPath = Path.of("native"),
				dataPath = Path.of("data"),
				warnings = emptyList(),
			),
		)
		val adapter = LifecycleAdapter(session, native)
		assertEquals(Dimensions(1280, 720), adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")))

		val images = adapter.acquireImages()
		assertNotNull(images)
		assertEquals(images, adapter.acquireImages())
		assertEquals(2, native.acquireCalls)
		assertTrue(adapter.releaseImages())
		assertTrue(adapter.releaseImages())
		assertEquals(2, native.releaseCalls)
		val rebuilt = adapter.acquireImages()
		assertNotNull(rebuilt)
		assertNotEquals(images, rebuilt)
		assertTrue(adapter.releaseImages())
		assertNull(session.failure)
	}

	@Test
	fun `not-ready session does not acquire evaluation images`() {
		val native = ImageNative()
		val session = DlssSession(
			DlssStartupConfig(
				enabled = true,
				qualityMode = SRMode.QUALITY,
				outputDimensions = Dimensions(2560, 1440),
				sdkPath = Path.of("sdk"),
				nativeLibraryPath = Path.of("native"),
				dataPath = Path.of("data"),
				warnings = emptyList(),
			),
		)
		assertNull(LifecycleAdapter(session, native).acquireImages())
		assertEquals(0, native.acquireCalls)
	}

	private class WritingNative : StreamlineSessionTestDouble() {
		var lastMotion: MotionRequest? = null
		override fun initialize(vkInstance: Long, vkPhysicalDevice: Long, vkDevice: Long, sdkPath: Path, dataPath: Path) = StreamlineSession.SUCCESS_RESULT
		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int) = Dimensions(1280, 720)
		override fun configureSuperResolution(outputWidth: Int, outputHeight: Int, renderWidth: Int, renderHeight: Int, qualityMode: Int, renderPreset: Int) = StreamlineSession.SUCCESS_RESULT
		override fun writeMotion(request: MotionRequest): Int { lastMotion = request; return StreamlineSession.SUCCESS_RESULT }
	}

	private inner class BarrierNative : StreamlineSessionTestDouble() {
		val taggedBuffers = mutableListOf<Long>()
		val evaluated = mutableListOf<EvaluationRequest>()
		override fun initialize(vkInstance: Long, vkPhysicalDevice: Long, vkDevice: Long, sdkPath: Path, dataPath: Path) = StreamlineSession.SUCCESS_RESULT
		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int) = Dimensions(1280, 720)
		override fun configureSuperResolution(outputWidth: Int, outputHeight: Int, renderWidth: Int, renderHeight: Int, qualityMode: Int, renderPreset: Int) = StreamlineSession.SUCCESS_RESULT
		override fun tagSrResources(request: SrTagRequest): Int { taggedBuffers += request.commandBuffer; return StreamlineSession.SUCCESS_RESULT }
		override fun evaluateSuperResolution(request: EvaluationRequest): Int { evaluated += request; return StreamlineSession.SUCCESS_RESULT }
	}

	private class ImageNative : StreamlineSessionTestDouble() {
		var acquireCalls = 0
		var releaseCalls = 0
		private var images: EvaluationImages? = null
		override fun initialize(vkInstance: Long, vkPhysicalDevice: Long, vkDevice: Long, sdkPath: Path, dataPath: Path) = StreamlineSession.SUCCESS_RESULT
		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int) = Dimensions(1280, 720)
		override fun configureSuperResolution(outputWidth: Int, outputHeight: Int, renderWidth: Int, renderHeight: Int, qualityMode: Int, renderPreset: Int) = StreamlineSession.SUCCESS_RESULT
		override fun acquireImages(): EvaluationImages {
			acquireCalls++
			return images ?: EvaluationImages(
				ImageBinding(100L + acquireCalls, 200L + acquireCalls, 83),
				ImageBinding(300L + acquireCalls, 400L + acquireCalls, 37),
			).also { images = it }
		}
		override fun releaseImages(): Int { releaseCalls++; images = null; return StreamlineSession.SUCCESS_RESULT }
	}
}
