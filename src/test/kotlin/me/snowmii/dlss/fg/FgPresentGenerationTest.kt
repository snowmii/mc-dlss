package me.snowmii.dlss.fg

import java.nio.file.Path
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.bridge.EvaluationRequest
import me.snowmii.dlss.bridge.ExtensionBootstrap
import me.snowmii.dlss.bridge.FgState
import me.snowmii.dlss.bridge.FgTagRequest
import me.snowmii.dlss.bridge.HeadlessVulkanFixture
import me.snowmii.dlss.bridge.ImageBinding
import me.snowmii.dlss.bridge.Native
import me.snowmii.dlss.bridge.NativeApi
import me.snowmii.dlss.bridge.SrTagRequest
import me.snowmii.dlss.bridge.Vec2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.lwjgl.vulkan.KHRSurface
import org.lwjgl.vulkan.KHRSwapchain
import org.lwjgl.vulkan.KHRWin32Surface
import org.lwjgl.vulkan.VK10

/**
 * M-11's last command-side claim: a live Streamline session on a real Win32-surface
 * swapchain acquires and presents THROUGH the interposed vkQueuePresentKHR, and the DLSS-G
 * plugin answers with generated frames - the rung's whole point is that the headless fixture
 * now presents, which every earlier rung explicitly did not.
 *
 * The interposer is the seam: every swapchain, acquire, and present call the fixture makes
 * resolves to sl.interposer.dll's exported wrappers, the exact functions Streamline's hook
 * list names (eCreateSwapchainKHR, eGetSwapchainImagesKHR, eAcquireNextImageKHR,
 * eQueuePresentKHR), so the DLSS-G plugin sees the surface, the swapchain, and every
 * present. The frame each present consumes is the composed frame the other rung proved
 * records - FG tag, SR tag, SR evaluation, FG re-declaration, handoff - recorded on one
 * command buffer and submitted before the present. DLSS-G's eBlockNoClientQueues mode means
 * the plugin reads the tagged inputs on its own queues after the present returns, so the
 * rung observes generation through the state the plugin itself reports: the status word
 * stays eDLSSGStatusOk (zero), the presented-frame counter advances across a present window,
 * and the input-processing completion fence value advances with every processed present -
 * the value waitFgInputsIdle would wait on.
 *
 * The swapchain is created with as many images as configureFg declared (the DLSS-G
 * back-buffer count), so adequacy is observed on the live object: the driver created at
 * least the declared count, in the R8G8B8A8_UNORM format the FG options record declares for
 * the backbuffer, at the configured output size.
 *
 * The whole scenario runs in ONE test method (and therefore one test fork) like every other
 * live rung: the close-path slShutdown is what makes the fork's exit clean, and a fork that
 * followed an unclean exit comes up with the plugin manager already initialized.
 */
class FgPresentGenerationTest {

	@Test
	fun `live Win32 swapchain presents through the interposer and DLSS-G state proves generation`(
		@TempDir dataPath: Path,
	) {
		val instanceExtensions = mutableListOf<String>()
		ExtensionBootstrap.queryInstanceExtensions().let(instanceExtensions::addAll)
		// The surface extensions are the fixture's own requirement, not Streamline's: the
		// interposer tracks surfaces created through vkCreateWin32SurfaceKHR, and the swapchain
		// needs a surface to be created against.
		instanceExtensions += KHRSurface.VK_KHR_SURFACE_EXTENSION_NAME
		instanceExtensions += KHRWin32Surface.VK_KHR_WIN32_SURFACE_EXTENSION_NAME

		// The production merge starts from Minecraft's {graphicsFamily: 1} queue map and
		// adds SL's extra graphics and compute queues; the first graphics family is
		// compute-capable on this workstation, so both merges land in the same family.
		val graphicsFamily = probeGraphicsQueueFamily()
		HeadlessVulkanFixture(
			instanceExtensions,
			{ instance, physicalDevice ->
				val extensions = mutableListOf<String>()
				ExtensionBootstrap.addDeviceExtensions(extensions, instance, physicalDevice)
				extensions += KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME
				extensions
			},
			// No validation layer: the DLSS-G plugin's present-path submits crash the Khronos
			// validation layer's state tracker (it dereferences a handle the layer never saw),
			// and this rung's oracle is the live DLSS-G state query, not validation. The
			// validation-clean claim belongs to the composed-frame rung, which does not present.
			false,
			mapOf(graphicsFamily to requirementsExtras()),
		).use { fixture ->
			Native.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
				)
				// The fixture creates one host queue in the family, so Streamline's own queues
				// start at index 1 - right after the host's, as slSetVulkanInfo records them.
				val hostQueueCount = 1
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.activateVulkanProxies(
						fixture.instanceAddress(),
						fixture.physicalDeviceAddress(),
						fixture.deviceAddress(),
						graphicsFamily,
						hostQueueCount,
						graphicsFamily,
						hostQueueCount,
					),
					"activation must succeed against the merged queue layout",
				)

				val outputWidth = 2560
				val outputHeight = 1440
				val dimensions = bridge.queryOptimalDimensions(outputWidth, outputHeight, 2)
				assertTrue(
					dimensions.width in 1..outputWidth &&
						dimensions.height in 1..outputHeight,
					"queried render dimensions must be in (0, output], got $dimensions",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.configure(
						outputWidth,
						outputHeight,
						dimensions.width,
						dimensions.height,
						2,
						11,
					),
					"configure must record the SR options for the stored configuration",
				)
				// The declared DLSS-G back-buffer count is the swapchain's image count; the
				// swapchain below is created with exactly this many images, so adequacy is
				// observed against the live object the session presents through.
				val declaredBackBuffers = 3
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.configureFg(declaredBackBuffers),
					"the 2x DLSS-G options must record with the declared back-buffer count",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.initialize(
						fixture.instanceAddress(),
						fixture.physicalDeviceAddress(),
						fixture.deviceAddress(),
						dataPath,
						dataPath,
					),
					"initialize must record the activated Vulkan tuple",
				)
				val images = bridge.acquireImages()
				assertTrue(images != null, "module images must be acquired before the frame")

				// The engine's render-sized colour and depth and its output-sized HUD-less and
				// UI targets, standing in for Minecraft's main target, depth texture, and the
				// split's two targets; the fixture leaves them in VK_IMAGE_LAYOUT_GENERAL, which
				// is where Minecraft rests its own textures.
				val color = fixture.createEngineImage(
					dimensions.width,
					dimensions.height,
					VK10.VK_FORMAT_R8G8B8A8_UNORM,
					VK10.VK_IMAGE_USAGE_SAMPLED_BIT or VK10.VK_IMAGE_USAGE_STORAGE_BIT,
					VK10.VK_IMAGE_ASPECT_COLOR_BIT,
				)
				val depth = fixture.createEngineImage(
					dimensions.width,
					dimensions.height,
					VK10.VK_FORMAT_D32_SFLOAT,
					VK10.VK_IMAGE_USAGE_SAMPLED_BIT or VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,
					VK10.VK_IMAGE_ASPECT_DEPTH_BIT,
				)
				val hudless = fixture.createEngineImage(
					outputWidth,
					outputHeight,
					VK10.VK_FORMAT_R8G8B8A8_UNORM,
					VK10.VK_IMAGE_USAGE_SAMPLED_BIT or VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
					VK10.VK_IMAGE_ASPECT_COLOR_BIT,
				)
				val ui = fixture.createEngineImage(
					outputWidth,
					outputHeight,
					VK10.VK_FORMAT_R8G8B8A8_UNORM,
					VK10.VK_IMAGE_USAGE_SAMPLED_BIT or VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
					VK10.VK_IMAGE_ASPECT_COLOR_BIT,
				)

				// The hidden Win32 window the surface binds to is sized to the output, so the
				// swapchain's extent is the output size the DLSS-G options declared for the
				// backbuffer, and its image count is the declared back-buffer count - the
				// adequacy the rung observes.
				val surface = fixture.createSurface(outputWidth, outputHeight)
				val swapchain = fixture.createSwapchain(surface, outputWidth, outputHeight, declaredBackBuffers)
				assertTrue(
					swapchain.imageCount() >= declaredBackBuffers,
					"the swapchain must have at least the declared DLSS-G back buffers, got " +
						swapchain.imageCount(),
				)
				assertEquals(
					VK10.VK_FORMAT_R8G8B8A8_UNORM,
					swapchain.imageFormat(),
					"the swapchain format must be the format the FG options declare for the backbuffer",
				)
				assertEquals(
					outputWidth,
					swapchain.width(),
					"the swapchain must be output-sized like the DLSS-G options declare",
				)
				assertEquals(
					outputHeight,
					swapchain.height(),
					"the swapchain must be output-sized like the DLSS-G options declare",
				)

				// The DLSS-G state window starts here: the query resets the presented-frame
				// counter, and nothing has presented yet, so the baseline is the empty window.
				val baseline = bridge.queryFgState()
				assertEquals(0, baseline.numFramesPresented, "no frame has presented yet")
				assertEquals(0, baseline.lastPresentInputsProcessingFenceValue, "no present has been processed yet")

				// Two complete composed frames, each acquired, tagged, submitted, and presented
				// through the interposed path. The DLSS-G plugin's input processing runs on its
				// own queues after each present returns, so the fence value is polled rather
				// than asserted on the spot.
				presentComposedFrame(
					bridge,
					fixture,
					swapchain,
					dimensions,
					outputWidth,
					outputHeight,
					color,
					depth,
					hudless,
					ui,
				)
				presentComposedFrame(
					bridge,
					fixture,
					swapchain,
					dimensions,
					outputWidth,
					outputHeight,
					color,
					depth,
					hudless,
					ui,
				)
				val fenceAfterTwo = awaitFenceValueAbove(bridge, baseline.lastPresentInputsProcessingFenceValue)

				// DLSS-G reports OK on a live session that has presented: status is the raw
				// eDLSSGStatusOk word (zero), and the fence advanced because the plugin read the
				// two presented frames' tagged inputs on its own queues.
				assertEquals(
					DLSSG_STATUS_OK,
					fenceAfterTwo.status,
					"DLSS-G must report eDLSSGStatusOk after the interposed presents",
				)
				assertTrue(
					fenceAfterTwo.lastPresentInputsProcessingFenceValue > 0L,
					"the input-processing completion fence must have advanced past zero",
				)

				// Two more presents in a clean query window: the counter is read once, after
				// both presents, so it counts exactly this window's frames.
				presentComposedFrame(
					bridge,
					fixture,
					swapchain,
					dimensions,
					outputWidth,
					outputHeight,
					color,
					depth,
					hudless,
					ui,
				)
				presentComposedFrame(
					bridge,
					fixture,
					swapchain,
					dimensions,
					outputWidth,
					outputHeight,
					color,
					depth,
					hudless,
					ui,
				)
				val afterFour = bridge.queryFgState()
				assertTrue(
					afterFour.numFramesPresented >= 2,
					"two frames must actually be presented per query window, got ${afterFour.numFramesPresented}",
				)
				assertEquals(
					DLSSG_STATUS_OK,
					afterFour.status,
					"DLSS-G must still report eDLSSGStatusOk",
				)
				// The completion fence advances with every processed present: the value after
				// the second window is strictly past the value after the first.
				assertTrue(
					afterFour.lastPresentInputsProcessingFenceValue >
						fenceAfterTwo.lastPresentInputsProcessingFenceValue,
					"the completion fence must advance with each presented frame: " +
						"${fenceAfterTwo.lastPresentInputsProcessingFenceValue} then " +
						afterFour.lastPresentInputsProcessingFenceValue,
				)
			}
		}
	}

	/**
	 * One complete present-driven frame: acquire a swapchain image, record the composed
	 * frame on one command buffer (FG tag, SR tag, SR evaluation, FG re-declaration, present
	 * handoff), transition the acquired image to the present layout, submit and wait, and
	 * present through the interposed vkQueuePresentKHR.
	 */
	private fun presentComposedFrame(
		bridge: NativeApi,
		fixture: HeadlessVulkanFixture,
		swapchain: HeadlessVulkanFixture.Swapchain,
		dimensions: DlssDimensions,
		outputWidth: Int,
		outputHeight: Int,
		color: HeadlessVulkanFixture.EngineImage,
		depth: HeadlessVulkanFixture.EngineImage,
		hudless: HeadlessVulkanFixture.EngineImage,
		ui: HeadlessVulkanFixture.EngineImage,
	) {
		// The DLSS-G Vulkan contract pairs every present with two binary semaphores: the
		// acquire semaphore the plugin signals when its workloads are submitted (the fixture
		// waits it before the frame records), and the present semaphore the plugin waits on
		// before adding its workloads, signaled by the frame's own submit.
		val acquired = fixture.acquireNextImage(swapchain.handle())
		val presentSemaphore = fixture.createBinarySemaphore()
		val frame = fixture.allocateAndBeginCommandBuffer()
		assertEquals(
			NativeApi.SUCCESS_RESULT,
			bridge.tagFgResources(
				FgTagRequest(
					commandBuffer = frame.address(),
					depth = ImageBinding(depth.view(), depth.image(), VK10.VK_FORMAT_D32_SFLOAT),
					hudless = ImageBinding(hudless.view(), hudless.image(), VK10.VK_FORMAT_R8G8B8A8_UNORM),
					ui = ImageBinding(ui.view(), ui.image(), VK10.VK_FORMAT_R8G8B8A8_UNORM),
				),
			),
			"the FG tag must record first on the caller's command buffer",
		)
		assertEquals(
			NativeApi.SUCCESS_RESULT,
			bridge.tagSrResources(
				SrTagRequest(
					commandBuffer = frame.address(),
					color = ImageBinding(color.view(), color.image(), VK10.VK_FORMAT_R8G8B8A8_UNORM),
					depth = ImageBinding(depth.view(), depth.image(), VK10.VK_FORMAT_D32_SFLOAT),
				),
			),
			"the SR tag must record last so the evaluation reads its SHADER_READ_ONLY declarations",
		)
		assertEquals(
			NativeApi.SUCCESS_RESULT,
			bridge.evaluate(
				EvaluationRequest(
					commandBuffer = frame.address(),
					color = ImageBinding(color.view(), color.image(), VK10.VK_FORMAT_R8G8B8A8_UNORM),
					depth = ImageBinding(depth.view(), depth.image(), VK10.VK_FORMAT_D32_SFLOAT),
					jitter = Vec2(0.25f, -0.5f),
					motionScale = Vec2(1f, 1f),
					frameTimeMilliseconds = 16.6f,
					resetHistory = true,
					renderDimensions = dimensions,
				),
			),
			"the SR evaluation must record on the tagged frame's shared buffer",
		)
		assertEquals(
			NativeApi.SUCCESS_RESULT,
			bridge.tagFgResources(
				FgTagRequest(
					commandBuffer = frame.address(),
					depth = ImageBinding(depth.view(), depth.image(), VK10.VK_FORMAT_D32_SFLOAT),
					hudless = ImageBinding(hudless.view(), hudless.image(), VK10.VK_FORMAT_R8G8B8A8_UNORM),
					ui = ImageBinding(ui.view(), ui.image(), VK10.VK_FORMAT_R8G8B8A8_UNORM),
				),
			),
			"the FG tag must re-record after the evaluation so the present path reads its " +
				"engine-resting declarations",
		)
		assertEquals(
			NativeApi.SUCCESS_RESULT,
			bridge.presentHandoff(),
			"the composed frame's complete equal-index tag set must hand off before the present",
		)
		fixture.recordPresentLayoutTransition(frame, swapchain.images()[acquired.index()])
		fixture.submitAndSignal(frame, presentSemaphore)
		assertEquals(
			VK10.VK_SUCCESS,
			fixture.present(swapchain.handle(), acquired.index(), presentSemaphore),
			"the interposed vkQueuePresentKHR must succeed",
		)
	}

	/**
	 * Polls the DLSS-G state until the input-processing completion fence advances past
	 * [previousValue], returning the last state read. The plugin reads the presented frames'
	 * tagged inputs on its own queues after the present returns, so the advance is not
	 * synchronous with vkQueuePresentKHR; the poll is the same discipline waitFgInputsIdle
	 * encodes, with a timeout so a plugin that stopped processing fails the rung instead of
	 * hanging it.
	 */
	private fun awaitFenceValueAbove(bridge: NativeApi, previousValue: Long): FgState {
		val deadline = System.nanoTime() + FENCE_ADVANCE_TIMEOUT_NANOSECONDS
		var state = bridge.queryFgState()
		while (state.lastPresentInputsProcessingFenceValue <= previousValue) {
			assertTrue(
				System.nanoTime() < deadline,
				"timed out waiting for the completion fence to advance past $previousValue " +
					"(status=${state.status}, last=${state.lastPresentInputsProcessingFenceValue})",
			)
			Thread.sleep(100)
			state = bridge.queryFgState()
		}
		return state
	}

	/** The summed extra graphics + compute queues the loaded SL features require. */
	private fun requirementsExtras(): Int {
		val requirements = Native.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				bridge.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
			)
			bridge.queryQueueRequirements()
		}
		return requirements.graphicsQueues + requirements.computeQueues
	}

	/**
	 * The family the fixture creates its queues in, discovered with a throwaway default fixture
	 * so the augmented fixture can be built with the extras keyed by the right family.
	 */
	private fun probeGraphicsQueueFamily(): Int =
		HeadlessVulkanFixture().use { it.graphicsQueueFamilyIndex() }

	private companion object {
		/** sl::DLSSGStatus::eOk = 0: everything is working as expected. */
		private const val DLSSG_STATUS_OK = 0

		/** The plugin reads presented inputs asynchronously; give it a generous window. */
		private const val FENCE_ADVANCE_TIMEOUT_NANOSECONDS = 120_000_000_000L
	}
}
