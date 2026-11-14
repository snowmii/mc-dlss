package me.snowmii.dlss.render

import me.snowmii.dlss.DlssSession
import me.snowmii.dlss.DlssStartupConfig
import me.snowmii.dlss.streamline.LifecycleAdapter
import me.snowmii.dlss.SRMode
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
import me.snowmii.dlss.api.RayReconstruction
import me.snowmii.dlss.api.RayReconstructionGuides
import me.snowmii.streamline.RrPrepareRequest
import me.snowmii.dlss.vk.VulkanContext
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
				destination = ImageBinding(901L, 900L, 37),
				destinationImageUsage = 8,
			),
			session.failure?.diagnostic(),
		)
		assertEquals(listOf("writeMotion", "srTag", "evaluate", "present"), native.order)
		assertEquals(1, taken.size)
		assertEquals(taken.single(), submitted.single())
		assertEquals(List(4) { taken.single().address() }, native.commandBuffers)
		val target = native.presentTargets.single()
		assertEquals(900L, target.image)
		assertEquals(901L, target.imageView)
		assertEquals(37, target.imageFormat)
		assertEquals(8, target.imageUsage)
		assertEquals(output, target.outputDimensions)
		evaluation.close()
	}

	private fun fakeCommandBuffer(): VkCommandBuffer {
		val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
		field.isAccessible = true
		val unsafe = field.get(null) as sun.misc.Unsafe
		return unsafe.allocateInstance(VkCommandBuffer::class.java) as VkCommandBuffer
	}

	@Test
	fun `rejected RR preparation or tags recover through SR with physical depth`(@TempDir dataPath: Path) {
		val oldEnabled = RayReconstruction.enabled
		val oldGuides = RayReconstruction.guides
		val scene = SceneResources(ImageBinding(201L, 202L, 37), ImageBinding(301L, 302L, 126))
		val guides = RayReconstructionGuides(ImageBinding(701L, 702L, 97), scene.color, scene.color, scene.color,
			depth = ImageBinding(801L, 802L, 126))
		RayReconstruction.enabled = true
		RayReconstruction.guides = java.util.function.Supplier { guides }
		try {
			for (rejectPrepare in listOf(true, false)) {
				val native = RecordingNative().apply {
					rejectRrPrepare = rejectPrepare
					rejectHdrTag = !rejectPrepare
				}
				val session = DlssSession(DlssStartupConfig(true, SRMode.QUALITY, outputDimensions = output,
					sdkPath = dataPath, nativeLibraryPath = null, dataPath = dataPath, warnings = emptyList()))
				val adapter = LifecycleAdapter(session, native)
				assertNotNull(adapter.initialize(1L, 2L, 3L, dataPath, dataPath))
				val context = VulkanContext.fromNativeHandles(1L, 2L, 3L, 4L, 0, 0, 0, 0, { fakeCommandBuffer() })
				FrameEvaluation(adapter, { context }).use { evaluation ->
					assertTrue(evaluation.evaluateFrame(scene, DlssJitterOffset(0, 0f, 0f, Dimensions(640, 360)),
						DlssFrameMotion(Matrix4f(), 320f, 180f, 16f, false), 900L,
						camera = DlssCameraSample(Matrix4f(), Matrix4f(), 0.0, 64.0, 0.0)))
					assertEquals(scene.depth, native.motionRequests.last().depth)
					assertEquals(scene.color, native.evaluatedRequests.single().color)
					assertEquals(scene.depth, native.evaluatedRequests.single().depth)
					assertTrue(native.evaluatedRequests.single().resetHistory)
					assertFalse(native.rrRequests.last().enabled)
					assertNull(native.presentTargets.single().reflection)
					assertNull(session.failure, "an optional guide refusal must not latch vanilla fallback")
				}
			}
		} finally {
			RayReconstruction.enabled = oldEnabled
			RayReconstruction.guides = oldGuides
		}
	}

	@Test
	fun `a profiler failure still returns the recorded command buffer`(@TempDir dataPath: Path) {
		val native = RecordingNative()
		val session = DlssSession(DlssStartupConfig(true, SRMode.QUALITY, outputDimensions = output,
			sdkPath = dataPath, nativeLibraryPath = null, dataPath = dataPath, warnings = emptyList()))
		val adapter = LifecycleAdapter(session, native)
		assertNotNull(adapter.initialize(1L, 2L, 3L, dataPath, dataPath))
		var submitted = 0
		val context = VulkanContext.fromNativeHandles(1L, 2L, 3L, 4L, 0, 0, 0, 0,
			{ fakeCommandBuffer() }, { submitted++ })
		val oldProfiler = me.snowmii.dlss.api.GpuProfiling.begin
		me.snowmii.dlss.api.GpuProfiling.begin = { _, _ -> AutoCloseable { error("profiler failed") } }
		try {
			FrameEvaluation(adapter, { context }).use { evaluation ->
				org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
					evaluation.evaluateFrame(SceneResources(ImageBinding(201L, 202L, 37), ImageBinding(301L, 302L, 126)),
						DlssJitterOffset(0, 0f, 0f, Dimensions(640, 360)),
						DlssFrameMotion(Matrix4f(), 320f, 180f, 16f, false))
				}
				assertEquals(1, submitted)
			}
		} finally {
			me.snowmii.dlss.api.GpuProfiling.begin = oldProfiler
		}
	}

	private class RecordingNative : StreamlineSessionTestDouble() {
		var rejectRrPrepare = false
		var rejectHdrTag = false
		val order = mutableListOf<String>()
		val commandBuffers = mutableListOf<Long>()
		val taggedSrRequests = mutableListOf<SrTagRequest>()
		val evaluatedRequests = mutableListOf<EvaluationRequest>()
		val rrRequests = mutableListOf<RrPrepareRequest>()
		val motionRequests = mutableListOf<MotionRequest>()
        val presentTargets = mutableListOf<PresentTarget>()

		override fun initialize(vkInstance: Long, vkPhysicalDevice: Long, vkDevice: Long, sdkPath: Path, dataPath: Path) = StreamlineSession.SUCCESS_RESULT
		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int) = Dimensions(640, 360)
		override fun queryOptimalDimensionsRr(outputWidth: Int, outputHeight: Int, qualityMode: Int) = Dimensions(640, 360)
		override fun configureSuperResolution(outputWidth: Int, outputHeight: Int, renderWidth: Int, renderHeight: Int, qualityMode: Int, renderPreset: Int) = StreamlineSession.SUCCESS_RESULT
		override fun acquireImages() = EvaluationImages(ImageBinding(401L, 402L, 124), ImageBinding(501L, 502L, 37))
		override fun writeMotion(request: MotionRequest): Int { commandBuffers += request.commandBuffer; motionRequests += request; order += "writeMotion"; return StreamlineSession.SUCCESS_RESULT }
		override fun tagSrResources(request: SrTagRequest): Int { commandBuffers += request.commandBuffer; taggedSrRequests += request; order += "srTag"; return if (rejectHdrTag && request.color.format == 97) 0xBAD00005.toInt() else StreamlineSession.SUCCESS_RESULT }
		override fun evaluateSuperResolution(request: EvaluationRequest): Int { commandBuffers += request.commandBuffer; evaluatedRequests += request; order += "evaluate"; return StreamlineSession.SUCCESS_RESULT }
		override fun presentOutput(target: PresentTarget): Int { presentTargets += target; commandBuffers += target.commandBuffer; order += "present"; return StreamlineSession.SUCCESS_RESULT }
		override fun prepareRayReconstruction(request: RrPrepareRequest): Int { rrRequests += request; return if (rejectRrPrepare && request.enabled) 0xBAD00005.toInt() else StreamlineSession.SUCCESS_RESULT }
	}

	@Test
	fun `when guides exist but camera is null super resolution falls back to scene color with ray reconstruction disabled`(
		@TempDir dataPath: Path,
	) {
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

		val context = VulkanContext.fromNativeHandles(
			1L, 2L, 3L, 4L, 0, 0, 0, 0,
			{ fakeCommandBuffer() },
			{ },
		)
		val evaluation = FrameEvaluation(adapter, context = { context })

		val sceneColor = ImageBinding(201L, 202L, 37)
		val sceneDepth = ImageBinding(301L, 302L, 126)
		val noisyHdrColor = ImageBinding(991L, 992L, 37)
		val virtualDepth = ImageBinding(1001L, 1002L, 100)
		val specularMotion = ImageBinding(1003L, 1004L, 83)
        val reflection = ImageBinding(1005L, 1006L, 97)
		val biasCurrentColor = ImageBinding(1007L, 1008L, 9)
		val guides = RayReconstructionGuides(
			noisyColor = noisyHdrColor,
			diffuseAlbedo = ImageBinding(993L, 994L, 37),
			specularAlbedo = ImageBinding(995L, 996L, 37),
			normalRoughness = ImageBinding(997L, 998L, 37),
			specularHitDistance = ImageBinding(999L, 1000L, 37),
			depth = virtualDepth,
			specularMotion = specularMotion,
            reflection = reflection,
			biasCurrentColor = biasCurrentColor,
		)

		val previousEnabled = RayReconstruction.enabled
		val previousGuides = RayReconstruction.guides
		RayReconstruction.enabled = true
		RayReconstruction.guides = java.util.function.Supplier { guides }
		try {
			assertTrue(
				evaluation.evaluateFrame(
					SceneResources(sceneColor, sceneDepth),
					DlssJitter(renderSize, output).advance(),
					DlssFrameMotion(Matrix4f(), renderSize.width / 2f, renderSize.height / 2f, 16.6f, true),
					900L,
					camera = null,
				),
				session.failure?.diagnostic(),
			)
			assertEquals(sceneColor, native.taggedSrRequests.last().color)
			assertEquals(sceneColor, native.evaluatedRequests.last().color)
			assertFalse(native.rrRequests.last().enabled)
			assertEquals(ImageBinding(0L, 0L, 0), native.rrRequests.last().biasCurrentColor)
            assertEquals(null, native.presentTargets.last().reflection)
			assertEquals(sceneDepth, native.taggedSrRequests.last().depth)
			assertEquals(listOf(sceneDepth), native.motionRequests.map { it.depth })

			val camera = DlssCameraSample(Matrix4f(), Matrix4f(), 10.0, 64.0, -20.0)
			assertTrue(
				evaluation.evaluateFrame(
					SceneResources(sceneColor, sceneDepth),
					DlssJitter(renderSize, output).advance(),
					DlssFrameMotion(Matrix4f(), renderSize.width / 2f, renderSize.height / 2f, 16.6f, true),
					901L,
					camera = camera,
				),
				session.failure?.diagnostic(),
			)
			assertEquals(noisyHdrColor, native.taggedSrRequests.last().color)
			assertEquals(noisyHdrColor, native.evaluatedRequests.last().color)
			assertTrue(native.rrRequests.last().enabled)
			assertEquals(biasCurrentColor, native.rrRequests.last().biasCurrentColor)
            assertEquals(reflection, native.presentTargets.last().reflection)
			assertEquals(specularMotion, native.rrRequests.last().specularMotion)
			assertEquals(virtualDepth, native.taggedSrRequests.last().depth)
			assertEquals(virtualDepth, native.evaluatedRequests.last().depth)
			assertEquals(listOf(sceneDepth, virtualDepth), native.motionRequests.map { it.depth })

			RayReconstruction.guides = java.util.function.Supplier { guides.copy(depth = null) }
			assertTrue(evaluation.evaluateFrame(
				SceneResources(sceneColor, sceneDepth), DlssJitter(renderSize, output).advance(),
				DlssFrameMotion(Matrix4f(), renderSize.width / 2f, renderSize.height / 2f, 16.6f, false),
				902L, camera = camera,
			))
			assertEquals(sceneDepth, native.motionRequests.last().depth)
			assertEquals(sceneDepth, native.taggedSrRequests.last().depth)
			assertEquals(specularMotion, native.rrRequests.last().specularMotion)

			RayReconstruction.guides = java.util.function.Supplier { guides.copy(biasCurrentColor = null) }
			assertTrue(evaluation.evaluateFrame(
				SceneResources(sceneColor, sceneDepth), DlssJitter(renderSize, output).advance(),
				DlssFrameMotion(Matrix4f(), renderSize.width / 2f, renderSize.height / 2f, 16.6f, false),
				902L, camera = camera,
			))
			assertTrue(native.rrRequests.last().enabled)
			assertEquals(ImageBinding(0L, 0L, 0), native.rrRequests.last().biasCurrentColor)
			assertFalse(native.evaluatedRequests.last().resetHistory, "removing hint preserves whole-frame history")

			val sequence = listOf(true to false, false to true, false to false, true to true, true to false)
			for ((hasGuides, expectedReset) in sequence) {
				RayReconstruction.guides = java.util.function.Supplier { if (hasGuides) guides else null }
				assertTrue(
					evaluation.evaluateFrame(
						SceneResources(sceneColor, sceneDepth),
						DlssJitter(renderSize, output).advance(),
						DlssFrameMotion(Matrix4f(), renderSize.width / 2f, renderSize.height / 2f, 16.6f, false),
						901L,
						camera = camera,
					),
				)
				assertEquals(expectedReset, native.evaluatedRequests.last().resetHistory, "hasGuides=$hasGuides")
				assertEquals(hasGuides, native.rrRequests.last().enabled)
				assertEquals(if (hasGuides) biasCurrentColor else ImageBinding(0L, 0L, 0), native.rrRequests.last().biasCurrentColor)
				assertEquals(if (hasGuides) noisyHdrColor else sceneColor, native.evaluatedRequests.last().color)
                assertEquals(if (hasGuides) reflection else null, native.presentTargets.last().reflection)
			}

			val resettingGuides = guides.copy(resetHistory = true)
			RayReconstruction.guides = java.util.function.Supplier { resettingGuides }
			assertTrue(
				evaluation.evaluateFrame(
					SceneResources(sceneColor, sceneDepth),
					DlssJitter(renderSize, output).advance(),
					DlssFrameMotion(Matrix4f(), renderSize.width / 2f, renderSize.height / 2f, 16.6f, false),
					901L,
					camera = camera,
				),
			)
			assertTrue(native.evaluatedRequests.last().resetHistory, "guides.resetHistory forces reset")

			RayReconstruction.guides = java.util.function.Supplier { guides }
			assertTrue(
				evaluation.evaluateFrame(
					SceneResources(sceneColor, sceneDepth),
					DlssJitter(renderSize, output).advance(),
					DlssFrameMotion(Matrix4f(), renderSize.width / 2f, renderSize.height / 2f, 16.6f, false),
					901L,
					camera = camera,
				),
			)
			assertFalse(native.evaluatedRequests.last().resetHistory, "normal frame accumulates")

			RayReconstruction.requestHistoryReset()
			assertTrue(
				evaluation.evaluateFrame(
					SceneResources(sceneColor, sceneDepth),
					DlssJitter(renderSize, output).advance(),
					DlssFrameMotion(Matrix4f(), renderSize.width / 2f, renderSize.height / 2f, 16.6f, false),
					901L,
					camera = camera,
				),
			)
			assertTrue(native.evaluatedRequests.last().resetHistory, "requestHistoryReset forces reset")

			assertTrue(
				evaluation.evaluateFrame(
					SceneResources(sceneColor, sceneDepth),
					DlssJitter(renderSize, output).advance(),
					DlssFrameMotion(Matrix4f(), renderSize.width / 2f, renderSize.height / 2f, 16.6f, false),
					901L,
					camera = camera,
				),
			)
			assertFalse(native.evaluatedRequests.last().resetHistory, "consumed reset returns to normal accumulation")
			assertEquals(biasCurrentColor, native.rrRequests.last().biasCurrentColor)

			RayReconstruction.enabled = false
			assertTrue(evaluation.evaluateFrame(
				SceneResources(sceneColor, sceneDepth), DlssJitter(renderSize, output).advance(),
				DlssFrameMotion(Matrix4f(), renderSize.width / 2f, renderSize.height / 2f, 16.6f, false),
				902L, camera = camera,
			))
			assertFalse(native.rrRequests.last().enabled)
			assertEquals(ImageBinding(0L, 0L, 0), native.rrRequests.last().biasCurrentColor)
			assertTrue(native.evaluatedRequests.last().resetHistory, "existing RR-to-SR switch reset unchanged")
		} finally {
			RayReconstruction.enabled = previousEnabled
			RayReconstruction.guides = previousGuides
			RayReconstruction.consumeReset()
			evaluation.close()
		}
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
