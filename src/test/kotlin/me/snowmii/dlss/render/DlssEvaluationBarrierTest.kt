package me.snowmii.dlss.render
import me.snowmii.streamline.Native
import me.snowmii.streamline.NativeApi
import me.snowmii.streamline.ImageBinding
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.streamline.ExtensionBootstrap
import me.snowmii.dlss.bridge.HeadlessVulkanFixture
import me.snowmii.streamline.SrTagRequest
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.SRMode
import me.snowmii.streamline.Dimensions
import me.snowmii.dlss.session.LifecycleAdapter

import java.nio.file.Files
import java.nio.file.Path
import me.snowmii.dlss.NativeBridge
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Proves the evaluation records its own layout transitions on the caller's command buffer, and
 * that the resulting recording actually submits and completes on a real graphics queue.
 *
 * Before this behavior was wired, the recording was incomplete: `mc_dlss_evaluate` built NGX
 * resources and called NGX, but nothing had put an image into the layout DLSS reads it in,
 * and no command buffer carrying DLSS work had been submitted. The native images were still
 * VK_IMAGE_LAYOUT_UNDEFINED.
 *
 * The 310.7.0 programming guide's Resource States section is the contract being kept: inputs in a
 * read state, output in a storage state, and DLSS restores both afterwards. Minecraft's own side
 * of it is that VulkanGpuTexture transitions every texture to VK_IMAGE_LAYOUT_GENERAL at creation
 * and the backend binds attachments and sampled images at GENERAL forever after, so GENERAL is
 * where the engine's colour and depth images have to be handed back.
 *
 * A wrong `oldLayout` is undefined behaviour, not an error: the driver stays silent and the image
 * is quietly garbage. The Khronos validation layer is the only oracle for it, so the fixture runs
 * with it enabled and the test asserts on what it reported. The final barrier the test records
 * itself - colour and depth from GENERAL - is the restoration claim: validation contradicts it if
 * the evaluation left them in the read layout.
 */
@NativeBridge
class DlssEvaluationBarrierTest {
	private val output = Dimensions(2560, 1440)

	@Test
	fun `evaluation records transitions that submit clean and restore engine images to general`(
		@TempDir dataPath: Path,
	) {
		val library = nativeLibrary()
		val instanceExtensions = ExtensionBootstrap.queryInstanceExtensions()

		// Streamline must bootstrap and record the device before the session starts: the
		// evaluation now runs through SL, so the device has to be created with SL's extensions
		// and merged queues and activated before the first frame. The queue requirements come
		// from a throwaway bridge closed before the device exists; the fixture then OUTLIVES
		// the bridge so Native.close's mc_dlss_close runs while the Vulkan device is alive.
		val requirements = Native.open(library).use { native ->
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				native.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
				"Streamline bootstrap must succeed before the device is created",
			)
			native.queryQueueRequirements()
		}
		val graphicsFamily = probeGraphicsQueueFamily()
		val extras = requirements.graphicsQueues + requirements.computeQueues
		HeadlessVulkanFixture(
			instanceExtensions,
			{ vkInstance, vkPhysicalDevice ->
				val extensions = mutableListOf<String>()
				ExtensionBootstrap.addDeviceExtensions(extensions, vkInstance, vkPhysicalDevice)
				extensions
			},
			true,
			mapOf(graphicsFamily to extras),
		).use { vulkan ->
			assertTrue(
				vulkan.validationEnabled(),
				"VK_LAYER_KHRONOS_validation must be installed; without it this test proves nothing about layouts",
			)

			Native.open(library).use { native ->
				// Bootstrap is idempotent across bridge instances: the runtime is already up.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					native.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
					"Streamline bootstrap must succeed before the device is created",
				)
				// The fixture creates one host queue in the family, so Streamline's own queues
				// start at index 1 - right after the host's, as slSetVulkanInfo records them.
				val hostQueueCount = 1
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					native.activateVulkanProxies(
						vulkan.instanceAddress(),
						vulkan.physicalDeviceAddress(),
						vulkan.deviceAddress(),
						graphicsFamily,
						hostQueueCount,
						graphicsFamily,
						hostQueueCount,
					),
					"SL proxy activation must succeed against the merged queue layout",
				)

				val session = DlssSession(
					DlssStartupConfig(
						enabled = true,
						qualityMode = SRMode.QUALITY,
						outputDimensions = output,
						sdkPath = dataPath,
						nativeLibraryPath = library,
						dataPath = dataPath,
						warnings = emptyList(),
					),
				)
				val adapter = LifecycleAdapter(session, native)
				val render = adapter.initialize(
					vulkan.instanceAddress(),
					vulkan.physicalDeviceAddress(),
					vulkan.deviceAddress(),
					dataPath,
					dataPath,
				)
				assertNotNull(render, session.failure?.diagnostic())

				val acquired = adapter.acquireImages()
				assertNotNull(acquired, session.failure?.diagnostic())
				val images = checkNotNull(acquired)

				// Stand-ins for the scene target the world phase renders into: render-sized, created
				// and left in GENERAL exactly as VulkanGpuTexture leaves Minecraft's own textures.
				val color = vulkan.createEngineImage(
					render!!.width,
					render.height,
					VK_FORMAT_R8G8B8A8_UNORM,
					VK_IMAGE_USAGE_SAMPLED_BIT or VK_IMAGE_USAGE_STORAGE_BIT or
						VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
					VK_IMAGE_ASPECT_COLOR_BIT,
				)
				val depth = vulkan.createEngineImage(
					render.width,
					render.height,
					VK_FORMAT_D32_SFLOAT,
					VK_IMAGE_USAGE_SAMPLED_BIT or VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,
					VK_IMAGE_ASPECT_DEPTH_BIT,
				)

				// The evaluation consumes the frame token the tag call obtains and retains, so the
				// frame's resources tag first on the same buffer, exactly as FrameEvaluation records
				// them in production.
				val commandBuffer = vulkan.allocateAndBeginCommandBuffer()
				assertTrue(
					adapter.tagSrResources(tagRequest(commandBuffer.address(), color, depth)),
					session.failure?.diagnostic(),
				)
				val evaluated = adapter.evaluate(
					evaluationRequest(commandBuffer.address(), color, depth),
				)
				assertTrue(evaluated, session.failure?.diagnostic())
				// A recorded command buffer proves nothing until the queue has run it.
				vulkan.endSubmitAndWait(commandBuffer)

				// The restoration claim: these barriers are only legal if the evaluation put both
				// engine images back in GENERAL, and validation says so if it did not.
				val after = vulkan.allocateAndBeginCommandBuffer()
				vulkan.recordGeneralLayoutBarrier(after, color)
				vulkan.recordGeneralLayoutBarrier(after, depth)
				vulkan.endSubmitAndWait(after)

				assertEquals(
					emptyList<String>(),
					vulkan.validationErrorsAbout(
						color.image(),
						depth.image(),
						images.motion.image,
						images.output.image,
					),
					"no validation error may name a resource this evaluation transitions",
				)

				// A second frame starts from the layouts the first one left behind, so it is the
				// cheapest check that the native images are tracked rather than re-transitioned
				// from UNDEFINED, which would discard what DLSS accumulated in them.
				val secondFrame = vulkan.allocateAndBeginCommandBuffer()
				assertTrue(
					adapter.tagSrResources(tagRequest(secondFrame.address(), color, depth)),
					session.failure?.diagnostic(),
				)
				assertTrue(
					adapter.evaluate(evaluationRequest(secondFrame.address(), color, depth)),
					session.failure?.diagnostic(),
				)
				vulkan.endSubmitAndWait(secondFrame)
				assertEquals(
					emptyList<String>(),
					vulkan.validationErrorsAbout(
						color.image(),
						depth.image(),
						images.motion.image,
						images.output.image,
					),
				)

				assertTrue(adapter.releaseImages())
			}
		}
	}

	/**
	 * The engine's two images and nothing else.
	 *
	 * The motion and output images are the bridge's own and are reached natively, so they are
	 * absent here even though the test acquires them - acquiring is what makes them exist, not
	 * what hands them over. The render dimensions are absent for a different reason: the adapter
	 * stamps the configured ones on the way through.
	 */
	private fun evaluationRequest(
		commandBuffer: Long,
		color: HeadlessVulkanFixture.EngineImage,
		depth: HeadlessVulkanFixture.EngineImage,
	): EvaluationRequest = EvaluationRequest.builder()
		.commandBuffer(commandBuffer)
		.color(ImageBinding(color.view(), color.image(), color.format()))
		.depth(ImageBinding(depth.view(), depth.image(), depth.format()))
		.frameTimeMilliseconds(16.6f)
		.resetHistory(true)
		.build()

	/** The frame's engine images, tagged the way FrameEvaluation tags them before evaluating. */
	private fun tagRequest(
		commandBuffer: Long,
		color: HeadlessVulkanFixture.EngineImage,
		depth: HeadlessVulkanFixture.EngineImage,
	): SrTagRequest = SrTagRequest(
		commandBuffer,
		ImageBinding(color.view(), color.image(), color.format()),
		ImageBinding(depth.view(), depth.image(), depth.format()),
	)

	/**
	 * The family the fixture creates its queues in, discovered with a throwaway default fixture
	 * so the augmented fixture can be built with the extras keyed by the right family.
	 */
	private fun probeGraphicsQueueFamily(): Int =
		HeadlessVulkanFixture().use { it.graphicsQueueFamilyIndex() }

	private fun nativeLibrary(): Path {
		val library = Path.of("").toAbsolutePath().resolve("streamline/build/native/mc_dlss.dll")
		assertTrue(Files.isRegularFile(library), "buildNativeDlss must produce mc_dlss.dll")
		return library
	}

	private companion object {
		/** Raw `VkFormat`, `VkImageUsageFlagBits`, and `VkImageAspectFlagBits` values. */
		const val VK_FORMAT_R8G8B8A8_UNORM = 37
		const val VK_FORMAT_D32_SFLOAT = 126
		const val VK_IMAGE_USAGE_SAMPLED_BIT = 0x4
		const val VK_IMAGE_USAGE_STORAGE_BIT = 0x8
		const val VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT = 0x10
		const val VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT = 0x20
		const val VK_IMAGE_ASPECT_COLOR_BIT = 0x1
		const val VK_IMAGE_ASPECT_DEPTH_BIT = 0x2
	}
}
