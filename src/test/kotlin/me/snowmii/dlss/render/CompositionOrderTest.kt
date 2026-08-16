package me.snowmii.dlss.render
import me.snowmii.dlss.bridge.PresentTarget
import me.snowmii.dlss.bridge.Native
import me.snowmii.dlss.bridge.NativeApi
import me.snowmii.dlss.bridge.ExtensionBootstrap
import me.snowmii.dlss.bridge.VulkanContext
import me.snowmii.dlss.bridge.ImageBinding
import me.snowmii.dlss.bridge.HeadlessVulkanFixture
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.SRMode
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.session.LifecycleAdapter

import java.nio.file.Files
import java.nio.file.Path
import me.snowmii.dlss.NativeBridge
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.lwjgl.vulkan.VkCommandBuffer

/**
 * M-11's rung: the upscaled frame reaches the target the rest of the frame composes over.
 *
 * Until this checkpoint DLSS ran and its result went nowhere. The output image is the bridge's own
 * and Minecraft has no handle for it, so the only way an upscaled frame becomes visible is by being
 * copied into the engine's output-sized target - after which hand and item, screen effects, the 3D
 * crosshair, HUD, and GUI all render on top of it at output resolution, exactly as they already do,
 * because the world phase closed before any of them ran.
 *
 * What this decides, and how each part fails:
 *
 * - **The copy happens, and carries the frame.** The destination starts black and holds the scene's
 *   colour afterwards.
 * - **It happens after the evaluation, on the same buffer.** Two frames are rendered in different
 *   colours. A copy recorded before the evaluation would leave the destination carrying the
 *   *previous* frame - the kind of one-frame lag that is invisible in a screenshot and obvious in
 *   motion.
 * - **The destination is handed back.** The barrier the test records afterwards claims
 *   VK_IMAGE_LAYOUT_GENERAL, which is where Minecraft rests every texture and where its next render
 *   pass expects to find this one; the Khronos validation layer contradicts the claim if it is
 *   false.
 * - **A target the configuration does not name is refused**, rather than scaled into, because a
 *   destination of the wrong size means the caller and the configuration disagree about what output
 *   resolution is.
 */
@NativeBridge
class CompositionOrderTest {
	private val output = DlssDimensions(1280, 720)

	@Test
	fun `the upscaled frame is copied into the engine target after the evaluation that produced it`(
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
				val session = DlssSession(startupConfig(library, dataPath, dataPath))
				val adapter = LifecycleAdapter(session, native)
				val render = adapter.initialize(
					vulkan.instanceAddress(),
					vulkan.physicalDeviceAddress(),
					vulkan.deviceAddress(),
					dataPath,
					dataPath,
				)
				assertNotNull(render, session.failure?.diagnostic())

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
				// Minecraft's main target: output-sized, created with the transfer, sampled, and
				// attachment usage vanilla gives every RenderTarget, and resting in GENERAL.
				val mainTarget = vulkan.createEngineImage(
					output.width,
					output.height,
					VK_FORMAT_R8G8B8A8_UNORM,
					VK_IMAGE_USAGE_TRANSFER_SRC_BIT or VK_IMAGE_USAGE_TRANSFER_DST_BIT or
						VK_IMAGE_USAGE_SAMPLED_BIT or VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
					VK_IMAGE_ASPECT_COLOR_BIT,
				)
				clearTo(vulkan, mainTarget, 0f, 0f, 0f)
				assertEquals(0f, channels(vulkan, mainTarget)[0], "the target must start black")

				val context = VulkanContext.fromNativeHandles(
					vulkan.instanceAddress(),
					vulkan.physicalDeviceAddress(),
					vulkan.deviceAddress(),
					vulkan.queueAddress(),
					commandBufferSource = { vulkan.allocateAndBeginCommandBuffer() },
					commandBufferSink = { buffer: VkCommandBuffer -> vulkan.endSubmitAndWait(buffer) },
				)
				val evaluation = FrameEvaluation(adapter, { context })
				val scene = SceneResources(
					color = ImageBinding(color.view(), color.image(), color.format()),
					depth = ImageBinding(depth.view(), depth.image(), depth.format()),
				)
				val jitter = DlssJitter(render, output)

				// Frame one, red.
				renderWorld(vulkan, color, depth, 1f, 0f, 0f)
				assertTrue(
					evaluation.evaluateFrame(scene, jitter.advance(), frameMotion(render, reset = true), mainTarget.image()),
					session.failure?.diagnostic(),
				)
				val firstFrame = channels(vulkan, mainTarget)
				assertTrue(
					firstFrame[0] > 0.25f && firstFrame[0] > firstFrame[1] && firstFrame[0] > firstFrame[2],
					"the target must hold the upscaled red scene, got ${firstFrame.toList()}",
				)

				// Frame two, green. A copy recorded before the evaluation would leave the target red.
				renderWorld(vulkan, color, depth, 0f, 1f, 0f)
				assertTrue(
					evaluation.evaluateFrame(scene, jitter.advance(), frameMotion(render, reset = false), mainTarget.image()),
					session.failure?.diagnostic(),
				)
				val secondFrame = channels(vulkan, mainTarget)
				assertTrue(
					secondFrame[1] > secondFrame[0],
					"the target must hold this frame's evaluation, not the previous one's, got ${secondFrame.toList()}",
				)

				// The restoration claim: this barrier is only legal if the copy put the engine's
				// target back in GENERAL, and validation says so if it did not.
				val after = vulkan.allocateAndBeginCommandBuffer()
				vulkan.recordGeneralLayoutBarrier(after, mainTarget)
				vulkan.endSubmitAndWait(after)

				// A size the configuration does not name is refused rather than scaled into. This
				// goes at the native seam on purpose: the adapter always names the session's own
				// output dimensions, so the refusal exists for the caller that has lost track of
				// them - and it has to record nothing, which is why the buffer submits clean.
				val refusal = vulkan.allocateAndBeginCommandBuffer()
				assertNotEquals(
					NativeApi.SUCCESS_RESULT,
					native.presentOutput(
						PresentTarget(
							commandBuffer = refusal.address(),
							image = mainTarget.image(),
							outputDimensions = DlssDimensions(output.width / 2, output.height / 2),
						),
					),
					"a destination size the configuration does not name must be refused",
				)
				// The output image is its own source; copying it onto itself is a caller mistake.
				assertNotEquals(
					NativeApi.SUCCESS_RESULT,
					native.presentOutput(
						PresentTarget(
							commandBuffer = refusal.address(),
							image = evaluation.evaluationImages!!.output.image,
							outputDimensions = output,
						),
					),
				)
				vulkan.endSubmitAndWait(refusal)

				assertEquals(
					emptyList<String>(),
					vulkan.validationErrorsAbout(
						color.image(),
						depth.image(),
						mainTarget.image(),
						evaluation.evaluationImages!!.output.image,
					),
					"no validation error may name a resource this composition transitions",
				)

				evaluation.close()
			}
		}
	}

	private fun frameMotion(render: DlssDimensions, reset: Boolean) = DlssFrameMotion(
		reprojection = Matrix4f(),
		motionScaleX = render.width / 2f,
		motionScaleY = render.height / 2f,
		frameTimeMillis = if (reset) 0f else 16.6f,
		reset = reset,
	)

	/** Fills the scene target the way one world phase would: a flat colour at half depth. */
	private fun renderWorld(
		vulkan: HeadlessVulkanFixture,
		color: HeadlessVulkanFixture.EngineImage,
		depth: HeadlessVulkanFixture.EngineImage,
		red: Float,
		green: Float,
		blue: Float,
	) {
		val world = vulkan.allocateAndBeginCommandBuffer()
		vulkan.recordColorClear(world, color.image(), red, green, blue, 1f)
		vulkan.recordDepthClear(world, depth, 0.5f)
		vulkan.endSubmitAndWait(world)
	}

	private fun clearTo(
		vulkan: HeadlessVulkanFixture,
		image: HeadlessVulkanFixture.EngineImage,
		red: Float,
		green: Float,
		blue: Float,
	) {
		val clear = vulkan.allocateAndBeginCommandBuffer()
		vulkan.recordColorClear(clear, image.image(), red, green, blue, 1f)
		vulkan.endSubmitAndWait(clear)
	}

	/** The centre pixel of the engine target, as red, green, blue, alpha. */
	private fun channels(vulkan: HeadlessVulkanFixture, image: HeadlessVulkanFixture.EngineImage): FloatArray {
		val pixels = vulkan.readRgba8Image(image.image(), output.width, output.height)
		val centre = ((output.height / 2) * output.width + output.width / 2) * 4
		return floatArrayOf(pixels[centre], pixels[centre + 1], pixels[centre + 2], pixels[centre + 3])
	}

	private fun startupConfig(library: Path, sdkPath: Path, dataPath: Path) = DlssStartupConfig(
		enabled = true,
		qualityMode = SRMode.QUALITY,
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

	/**
	 * The family the fixture creates its queues in, discovered with a throwaway default fixture
	 * so the augmented fixture can be built with the extras keyed by the right family.
	 */
	private fun probeGraphicsQueueFamily(): Int =
		HeadlessVulkanFixture().use { it.graphicsQueueFamilyIndex() }

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
