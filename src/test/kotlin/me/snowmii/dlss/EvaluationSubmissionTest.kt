package me.snowmii.dlss

import java.nio.file.Files
import java.nio.file.Path
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.lwjgl.vulkan.VkCommandBuffer

/**
 * M-10's rung: the whole native path, driven for one frame by the production seam the renderer
 * uses, on a command buffer that belongs to the engine's submission.
 *
 * Every piece beneath this had its own rung and no caller. The bridge could allocate its images,
 * fill the motion image, and evaluate DLSS; the renderer could route the world into a
 * low-resolution target with coherent jitter and motion. Nothing joined them, so "DLSS evaluates
 * each displayed world frame" was still entirely unproven.
 *
 * Three things are decided here, and each of them fails a different way:
 *
 * - **Order.** The motion pass and the evaluation are recorded on one buffer, motion first. If
 *   they were reversed or split across buffers, DLSS would read a motion image from the previous
 *   frame or from nothing at all - silently, and only visible as a quality collapse in a live
 *   frame.
 * - **Ownership of submission.** The evaluation records and hands the buffer back; it never
 *   submits. The sentinel readback taken *before* the buffer is submitted is what proves it: a
 *   frame evaluation that submitted or idled the device on its own would already have overwritten
 *   the sentinel by the time it returned, and would be stalling Minecraft's render thread on every
 *   single frame.
 * - **Restoration.** The engine's colour and depth come back in VK_IMAGE_LAYOUT_GENERAL. The
 *   barriers this test records afterwards claim exactly that, and the Khronos validation layer
 *   contradicts them if the claim is false.
 */
class EvaluationSubmissionTest {
	private val output = DlssDimensions(1280, 720)

	@Test
	fun `one frame records motion and evaluation in order on the engine's own submission`(
		@TempDir dataPath: Path,
	) {
		val library = nativeLibrary()
		val ngxRuntime = ngxRuntimeDirectory()
		val instanceExtensions = DlssNative.open(library).use { it.queryInstanceExtensions() }

		HeadlessVulkanFixture(
			instanceExtensions,
			{ vkInstance, vkPhysicalDevice ->
				DlssNative.open(library).use { it.queryDeviceExtensions(vkInstance, vkPhysicalDevice) }
			},
			true,
		).use { vulkan ->
			assertTrue(
				vulkan.validationEnabled(),
				"VK_LAYER_KHRONOS_validation must be installed; without it this test proves nothing about layouts",
			)

			DlssNative.open(library).use { native ->
				val session = DlssSession(startupConfig(library, ngxRuntime, dataPath))
				val adapter = DlssLifecycleAdapter(session, native)
				val render = adapter.initialize(
					vulkan.instanceAddress(),
					vulkan.physicalDeviceAddress(),
					vulkan.deviceAddress(),
					ngxRuntime,
					dataPath,
				)
				assertNotNull(render, session.failure?.diagnostic())

				// The scene target the world phase renders into: render-sized, created and left in
				// GENERAL exactly as VulkanGpuTexture leaves Minecraft's own textures.
				val color = vulkan.createEngineImage(
					render!!.width,
					render.height,
					VK_FORMAT_R8G8B8A8_UNORM,
					VK_IMAGE_USAGE_SAMPLED_BIT or VK_IMAGE_USAGE_STORAGE_BIT or
						VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or VK_IMAGE_USAGE_TRANSFER_DST_BIT,
					VK_IMAGE_ASPECT_COLOR_BIT,
				)
				val depth = vulkan.createEngineImage(
					render.width,
					render.height,
					VK_FORMAT_D32_SFLOAT,
					VK_IMAGE_USAGE_SAMPLED_BIT or VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT or
						VK_IMAGE_USAGE_TRANSFER_DST_BIT,
					VK_IMAGE_ASPECT_DEPTH_BIT,
				)

				// A marker of the test's own, touched by nothing else, so its value answers exactly
				// one question: has the recorded buffer executed yet? The native images cannot answer
				// it - the bridge tracks their layouts, and a readback would move them behind its back.
				val marker = vulkan.createEngineImage(
					1,
					1,
					VK_FORMAT_R8G8B8A8_UNORM,
					VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
					VK_IMAGE_ASPECT_COLOR_BIT,
				)
				clearMarker(vulkan, marker)

				// Stands in for Minecraft's shared VulkanCommandEncoder: the evaluation draws its
				// buffer from the source and gives it back to the sink, and only the sink submits.
				val taken = mutableListOf<VkCommandBuffer>()
				val submitted = mutableListOf<VkCommandBuffer>()
				val deferred = mutableListOf<VkCommandBuffer>()
				var defer = false
				val context = DlssVulkanContext.fromNativeHandles(
					vulkan.instanceAddress(),
					vulkan.physicalDeviceAddress(),
					vulkan.deviceAddress(),
					vulkan.queueAddress(),
					commandBufferSource = {
						vulkan.allocateAndBeginCommandBuffer().also { buffer ->
							taken += buffer
							// Recorded at the head of the buffer the frame is about to use, so the
							// marker turns white exactly when that buffer executes - and not before.
							vulkan.recordColorClear(buffer, marker.image(), 1f, 1f, 1f, 1f)
						}
					},
					commandBufferSink = { buffer ->
						submitted += buffer
						if (defer) deferred += buffer else vulkan.endSubmitAndWait(buffer)
					},
				)

				val evaluation = DlssFrameEvaluation(adapter, { context })
				val scene = DlssSceneResources(
					colorView = color.view(),
					colorImage = color.image(),
					colorFormat = color.format(),
					depthView = depth.view(),
					depthImage = depth.image(),
					depthFormat = depth.format(),
				)

				// The world render this frame consumes: a flat red scene at half depth. A uniform
				// colour is what makes the upscaled output checkable at all - whatever DLSS does
				// with the history, red in must not come out black or blue.
				renderWorld(vulkan, color, depth)

				// The motion image carries a sentinel no dispatch would ever write, so the readback
				// below distinguishes "recorded but not yet run" from "already submitted".
				defer = true
				val motion = DlssFrameMotion(
					reprojection = Matrix4f(),
					motionScaleX = render.width / 2f,
					motionScaleY = render.height / 2f,
					frameTimeMillis = 0f,
					reset = true,
				)
				val jitter = DlssJitter(render, output).advance()
				assertTrue(evaluation.evaluateFrame(scene, jitter, motion), session.failure?.diagnostic())

				assertEquals(1, taken.size, "the frame must take exactly one command buffer")
				assertEquals(1, submitted.size, "the frame must hand back exactly one command buffer")
				assertSame(taken[0], submitted[0], "the buffer recorded must be the buffer handed back")

				// Nothing has run yet: the evaluation recorded and returned, and the buffer is still
				// sitting in the caller's hands.
				assertEquals(
					0f,
					vulkan.readRgba8Image(marker.image(), 1, 1)[0],
					"the frame evaluation must not submit or wait on its own recording",
				)

				vulkan.endSubmitAndWait(deferred.single())
				deferred.clear()
				defer = false

				assertEquals(1f, vulkan.readRgba8Image(marker.image(), 1, 1)[0], "the recorded buffer must have run")

				val images = evaluation.evaluationImages
				assertNotNull(images, session.failure?.diagnostic())
				assertUpscaledRed(vulkan.readRgba8Image(images!!.outputImage, output.width, output.height))

				// The restoration claim: these barriers are only legal if the recording put both
				// engine images back in GENERAL, and validation says so if it did not.
				val after = vulkan.allocateAndBeginCommandBuffer()
				vulkan.recordGeneralLayoutBarrier(after, color)
				vulkan.recordGeneralLayoutBarrier(after, depth)
				vulkan.endSubmitAndWait(after)

				// A second frame, with accumulated history rather than a reset, reuses the images the
				// first one acquired rather than allocating past what the configuration named.
				renderWorld(vulkan, color, depth)
				assertTrue(
					evaluation.evaluateFrame(
						scene,
						DlssJitter(render, output).advance(),
						motion.copy(frameTimeMillis = 16.6f, reset = false),
					),
					session.failure?.diagnostic(),
				)
				assertEquals(2, taken.size)
				assertEquals(2, submitted.size)
				assertSame(images, evaluation.evaluationImages, "a second frame must not re-acquire the images")

				assertEquals(
					emptyList<String>(),
					vulkan.validationErrorsAbout(
						color.image(),
						depth.image(),
						images.motionImage,
						images.outputImage,
					),
					"no validation error may name a resource this frame transitions",
				)

				evaluation.close()
			}
		}
	}

	@Test
	fun `a session that never reached ready records nothing and takes no command buffer`(
		@TempDir dataPath: Path,
	) {
		val library = nativeLibrary()
		val session = DlssSession(
			startupConfig(library, dataPath, dataPath).copy(enabled = false),
		)
		var taken = 0
		var submitted = 0
		val context = DlssVulkanContext.fromNativeHandles(
			1L,
			2L,
			3L,
			4L,
			commandBufferSource = {
				taken++
				throw AssertionError("no command buffer may be taken without a ready session")
			},
			commandBufferSink = { submitted++ },
		)
		val evaluation = DlssFrameEvaluation(DlssLifecycleAdapter(session, UnusableNative()), { context })

		assertFalse(
			evaluation.evaluateFrame(
				DlssSceneResources(1L, 2L, 37, 3L, 4L, 126),
				DlssJitterOffset(0, 0f, 0f, DlssDimensions(640, 360)),
				DlssFrameMotion(Matrix4f(), 320f, 180f, 0f, true),
			),
		)
		assertEquals(0, taken)
		assertEquals(0, submitted)
	}

	/** Fills the scene target the way one world phase would: flat red at half depth. */
	private fun renderWorld(
		vulkan: HeadlessVulkanFixture,
		color: HeadlessVulkanFixture.EngineImage,
		depth: HeadlessVulkanFixture.EngineImage,
	) {
		val world = vulkan.allocateAndBeginCommandBuffer()
		vulkan.recordColorClear(world, color.image(), 1f, 0f, 0f, 1f)
		vulkan.recordDepthClear(world, depth, 0.5f)
		vulkan.endSubmitAndWait(world)
	}

	/** Puts the marker in its unrun state and proves it got there. */
	private fun clearMarker(vulkan: HeadlessVulkanFixture, marker: HeadlessVulkanFixture.EngineImage) {
		val clear = vulkan.allocateAndBeginCommandBuffer()
		vulkan.recordColorClear(clear, marker.image(), 0f, 0f, 0f, 1f)
		vulkan.endSubmitAndWait(clear)
		assertEquals(0f, vulkan.readRgba8Image(marker.image(), 1, 1)[0], "the marker must start black")
	}

	/**
	 * Asserts the upscaled output holds the scene's red rather than an untouched allocation.
	 *
	 * DLSS is not a filter with a predictable per-pixel result, so this asserts what it cannot
	 * plausibly get wrong on a uniform frame: red dominates, and the image is not empty.
	 */
	private fun assertUpscaledRed(pixels: FloatArray) {
		val center = ((output.height / 2) * output.width + output.width / 2) * 4
		val red = pixels[center]
		val green = pixels[center + 1]
		val blue = pixels[center + 2]
		assertTrue(
			red > 0.25f && red > green && red > blue,
			"upscaled output must carry the scene colour, got r=$red g=$green b=$blue",
		)
	}

	private fun startupConfig(library: Path, sdkPath: Path, dataPath: Path) = DlssStartupConfig(
		enabled = true,
		qualityMode = DlssQualityMode.QUALITY,
		outputDimensions = output,
		sdkPath = sdkPath,
		nativeLibraryPath = library,
		dataPath = dataPath,
		warnings = emptyList(),
	)

	private fun nativeLibrary(): Path {
		val library = Path.of("").toAbsolutePath().resolve("build/native/mc_dlss.dll")
		assertTrue(Files.isRegularFile(library), "buildNativeDlss must produce mc_dlss.dll")
		return library
	}

	private fun ngxRuntimeDirectory(): Path {
		val runtime = Path.of(
			"C:/Users/miuki/Development/NVIDIA/mc-dlss/dlss-sdk-v310.7.0/DLSS-310.7.0/lib/Windows_x86_64/rel",
		)
		assertTrue(Files.isDirectory(runtime), "Pinned NGX runtime directory must exist")
		return runtime
	}

	/** Fails every call: a session that never reached READY must not reach the native side at all. */
	private class UnusableNative : DlssNativeApi {
		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		): Int = unreachable()

		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int): DlssDimensions =
			unreachable()

		override fun configure(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		): Int = unreachable()

		override fun acquireImages(): DlssEvaluationImages = unreachable()

		override fun releaseImages(): Int = unreachable()

		override fun writeMotion(
			commandBuffer: Long,
			depthView: Long,
			depthImage: Long,
			depthFormat: Int,
			depthAspectMask: Int,
			depthBaseMipLevel: Int,
			depthLevelCount: Int,
			depthBaseArrayLayer: Int,
			depthLayerCount: Int,
			reprojection: FloatArray,
			renderWidth: Int,
			renderHeight: Int,
		): Int = unreachable()

		override fun presentOutput(
			commandBuffer: Long,
			destinationImage: Long,
			destinationAspectMask: Int,
			destinationBaseMipLevel: Int,
			destinationLevelCount: Int,
			destinationBaseArrayLayer: Int,
			destinationLayerCount: Int,
			destinationWidth: Int,
			destinationHeight: Int,
		): Int = unreachable()

		@Suppress("LongParameterList")
		override fun evaluate(
			commandBuffer: Long,
			colorView: Long,
			colorImage: Long,
			colorFormat: Int,
			colorAspectMask: Int,
			colorBaseMipLevel: Int,
			colorLevelCount: Int,
			colorBaseArrayLayer: Int,
			colorLayerCount: Int,
			depthView: Long,
			depthImage: Long,
			depthFormat: Int,
			depthAspectMask: Int,
			depthBaseMipLevel: Int,
			depthLevelCount: Int,
			depthBaseArrayLayer: Int,
			depthLayerCount: Int,
			motionView: Long,
			motionImage: Long,
			motionFormat: Int,
			motionAspectMask: Int,
			motionBaseMipLevel: Int,
			motionLevelCount: Int,
			motionBaseArrayLayer: Int,
			motionLayerCount: Int,
			outputView: Long,
			outputImage: Long,
			outputFormat: Int,
			outputAspectMask: Int,
			outputBaseMipLevel: Int,
			outputLevelCount: Int,
			outputBaseArrayLayer: Int,
			outputLayerCount: Int,
			renderWidth: Int,
			renderHeight: Int,
			outputWidth: Int,
			outputHeight: Int,
			jitterX: Float,
			jitterY: Float,
			motionScaleX: Float,
			motionScaleY: Float,
			frameTimeMilliseconds: Float,
			resetHistory: Boolean,
		): Int = unreachable()

		private fun unreachable(): Nothing = throw AssertionError("native must not be reached")
	}

	private companion object {
		/** Raw `VkFormat`, `VkImageUsageFlagBits`, and `VkImageAspectFlagBits` values. */
		const val VK_FORMAT_R8G8B8A8_UNORM = 37
		const val VK_FORMAT_D32_SFLOAT = 126
		const val VK_IMAGE_USAGE_TRANSFER_SRC_BIT = 0x1
		const val VK_IMAGE_USAGE_TRANSFER_DST_BIT = 0x2
		const val VK_IMAGE_USAGE_SAMPLED_BIT = 0x4
		const val VK_IMAGE_USAGE_STORAGE_BIT = 0x8
		const val VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT = 0x10
		const val VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT = 0x20
		const val VK_IMAGE_ASPECT_COLOR_BIT = 0x1
		const val VK_IMAGE_ASPECT_DEPTH_BIT = 0x2
	}
}
