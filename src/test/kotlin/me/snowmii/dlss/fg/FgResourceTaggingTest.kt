package me.snowmii.dlss.fg

import java.nio.file.Path
import me.snowmii.dlss.NativeBridge
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.bridge.DlssEvaluationImages
import me.snowmii.dlss.bridge.DlssFrameTimings
import me.snowmii.dlss.bridge.EvaluationRequest
import me.snowmii.dlss.bridge.ExtensionBootstrap
import me.snowmii.dlss.bridge.FgTagRequest
import me.snowmii.dlss.bridge.HeadlessVulkanFixture
import me.snowmii.dlss.bridge.ImageBinding
import me.snowmii.dlss.bridge.MotionRequest
import me.snowmii.dlss.bridge.Native
import me.snowmii.dlss.bridge.NativeApi
import me.snowmii.dlss.bridge.PresentTarget
import me.snowmii.dlss.session.DlssNativeStage
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssSessionState
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.session.SRMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.lwjgl.vulkan.VK10

/**
 * M-9 slice rung: the DLSS-G frame resources tag through the native/Kotlin ABI seam, the
 * adapter gates them on READY, and failures latch.
 *
 * The live scenario drives the seam itself, mirroring the SR tag rung: a fresh fork's module
 * has no Streamline session, so [NativeApi.tagFgResources] must answer FAIL_NotInitialized
 * before bootstrap; a ready session refuses malformed inputs with FAIL_InvalidParameter; and
 * once the session is ready, dimensions/options are stored, the FG options have recorded, and
 * the module's images are acquired, the call records the frame's four DLSS-G tags - depth,
 * motion, HUD-less colour, and UI colour+alpha - on ONE allocated command buffer using ONE
 * retained frame token, and that buffer must submit clean under the Khronos validation layer.
 * A second tag on the same frame reuses the retained token and must also succeed.
 *
 * The refused-input cases pin the rung's lifecycle order, not just its shape: a fully-formed
 * request before the FG options recorded, and again before the module's images were
 * acquired, must both answer FAIL_InvalidParameter (the call refuses a partial tag set rather
 * than submitting one), and each image's format is checked against the formats the FG options
 * recorded - depth must be D32_SFLOAT, HUD-less and UI R8G8B8A8_UNORM.
 *
 * The whole scenario runs in ONE test method (and therefore one test fork) like the M-3 SR
 * rungs and the M-9 options rung: the close-path slShutdown is what makes the fork's exit
 * clean, and a fork that followed an unclean exit comes up with the plugin manager already
 * initialized.
 */
@NativeBridge
class FgResourceTaggingTest {

	@Test
	fun `FG tags record depth motion hudless and ui after ready session and acquired images`(
		@TempDir dataPath: Path,
	) {
		// Pre-init: this fork's module has never bootstrapped, so the tag has no Streamline
		// session to answer through. The check runs before the live session below and on a
		// throwaway bridge, and the module's bootstrap state is what it asserts against.
		Native.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
			assertEquals(
				FAIL_NOT_INITIALIZED,
				bridge.tagFgResources(FgTagRequest()),
				"tagFgResources before bootstrap must answer FAIL_NotInitialized",
			)
		}

		val instanceExtensions = ExtensionBootstrap.queryInstanceExtensions()

		// The production merge starts from Minecraft's {graphicsFamily: 1} queue map and
		// adds SL's extra graphics and compute queues; the first graphics family is
		// compute-capable on this workstation, so both merges land in the same family.
		val graphicsFamily = probeGraphicsQueueFamily()
		HeadlessVulkanFixture(
			instanceExtensions,
			{ instance, physicalDevice ->
				val extensions = mutableListOf<String>()
				ExtensionBootstrap.addDeviceExtensions(extensions, instance, physicalDevice)
				extensions
			},
			true,
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

				// Malformed inputs against the ready session: no command buffer, and a request
				// whose HUD-less image was never filled. Both must be refused before anything
				// is recorded.
				assertEquals(
					FAIL_INVALID_PARAMETER,
					bridge.tagFgResources(FgTagRequest(commandBuffer = 0)),
					"a tag without a command buffer must answer FAIL_InvalidParameter",
				)
				assertEquals(
					FAIL_INVALID_PARAMETER,
					bridge.tagFgResources(
						FgTagRequest(
							commandBuffer = 1L,
							depth = ImageBinding(301L, 302L, VK10.VK_FORMAT_D32_SFLOAT),
						),
					),
					"a tag with an unfilled HUD-less image must answer FAIL_InvalidParameter",
				)
				// A fully-formed request (all three images present with the recorded formats)
				// is still refused here: the DLSS-G options have not recorded yet, and the
				// frame's four tags never submit as a partial set.
				assertEquals(
					FAIL_INVALID_PARAMETER,
					bridge.tagFgResources(validTagRequest()),
					"a tag before the FG options recorded must answer FAIL_InvalidParameter",
				)

				val outputWidth = 2560
				val outputHeight = 1440
				// MaxQuality = 2 (NVSDK_NGX_PerfQuality_Value), which the bridge maps onto
				// sl::DLSSMode::eMaxQuality; preset K = 11 lands on the qualityPreset field.
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
					"configure must record the SL options for the stored configuration",
				)
				// The DLSS-G options record: the frame's tags name the same extents and
				// formats the options declared, so the tag contract reads from the same stored
				// configuration the options record did.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.configureFg(3),
					"after a ready session and a stored configuration the FG options must record",
				)
				// Options recorded, images not acquired yet: the motion source does not exist
				// at the configured size, so the four-tag set still cannot submit.
				assertEquals(
					FAIL_INVALID_PARAMETER,
					bridge.tagFgResources(validTagRequest()),
					"a tag before the module's images were acquired must answer FAIL_InvalidParameter",
				)
				// initialize records the activated tuple (arming the bridge's close path to
				// shut Streamline down while the device is alive) and makes the session ready
				// for image acquisition.
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
				// The motion tag records only once the module's own motion image exists at the
				// configured size, exactly like the SR tag's motion slot.
				val images = bridge.acquireImages()
				assertTrue(images != null, "module images must be acquired before the four tags")

				// Formats are checked against the ones the FG options recorded: each image's
				// role has exactly one accepted format, and anything else is refused even with
				// valid handles and a valid lifecycle.
				assertEquals(
					FAIL_INVALID_PARAMETER,
					bridge.tagFgResources(
						FgTagRequest(
							commandBuffer = 1L,
							depth = ImageBinding(301L, 302L, VK10.VK_FORMAT_R8G8B8A8_UNORM),
							hudless = ImageBinding(401L, 402L, VK10.VK_FORMAT_R8G8B8A8_UNORM),
							ui = ImageBinding(501L, 502L, VK10.VK_FORMAT_R8G8B8A8_UNORM),
						),
					),
					"a depth image not in the recorded D32_SFLOAT format must answer " +
						"FAIL_InvalidParameter",
				)
				assertEquals(
					FAIL_INVALID_PARAMETER,
					bridge.tagFgResources(
						FgTagRequest(
							commandBuffer = 1L,
							depth = ImageBinding(301L, 302L, VK10.VK_FORMAT_D32_SFLOAT),
							hudless = ImageBinding(401L, 402L, VK10.VK_FORMAT_B8G8R8A8_UNORM),
							ui = ImageBinding(501L, 502L, VK10.VK_FORMAT_R8G8B8A8_UNORM),
						),
					),
					"a HUD-less image not in the recorded R8G8B8A8_UNORM format must answer " +
						"FAIL_InvalidParameter",
				)
				assertEquals(
					FAIL_INVALID_PARAMETER,
					bridge.tagFgResources(
						FgTagRequest(
							commandBuffer = 1L,
							depth = ImageBinding(301L, 302L, VK10.VK_FORMAT_D32_SFLOAT),
							hudless = ImageBinding(401L, 402L, VK10.VK_FORMAT_R8G8B8A8_UNORM),
							ui = ImageBinding(501L, 502L, VK10.VK_FORMAT_B8G8R8A8_UNORM),
						),
					),
					"a UI image not in the recorded R8G8B8A8_UNORM format must answer " +
						"FAIL_InvalidParameter",
				)

				// The engine's render-sized depth and output-sized HUD-less + UI colour+alpha
				// targets, standing in for Minecraft's depth texture and the split's two
				// output-sized targets; the fixture leaves them in VK_IMAGE_LAYOUT_GENERAL,
				// which is where Minecraft rests its own textures.
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
				val tagRequest = FgTagRequest(
					commandBuffer = 0,
					depth = ImageBinding(depth.view(), depth.image(), VK10.VK_FORMAT_D32_SFLOAT),
					hudless = ImageBinding(hudless.view(), hudless.image(), VK10.VK_FORMAT_R8G8B8A8_UNORM),
					ui = ImageBinding(ui.view(), ui.image(), VK10.VK_FORMAT_R8G8B8A8_UNORM),
				)

				// The frame's four tags record on ONE buffer: depth, motion, HUD-less, and UI
				// colour+alpha, all with retained-present lifetime under ONE frame token. The
				// second tag reuses the retained token rather than advancing the frame, which
				// is what keeps every tag of the frame on the same frame index.
				val frame = fixture.allocateAndBeginCommandBuffer()
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagFgResources(tagRequest.copy(commandBuffer = frame.address())),
					"the frame's DLSS-G resources must tag on the caller's command buffer",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagFgResources(tagRequest.copy(commandBuffer = frame.address())),
					"a repeated tag for the same frame must reuse the retained frame token",
				)
				fixture.endSubmitAndWait(frame)
				assertFgTagValidationClean(fixture, depth, hudless, ui, images!!)
			}
		}
	}

	@Test
	fun `adapter gates FG tags on READY and latches failures`() {
		val native = FakeNative()
		val outputDimensions = DlssDimensions(2560, 1440)
		val session = DlssSession(config(outputDimensions))
		val adapter = LifecycleAdapter(session, native)
		val request = FgTagRequest(
			commandBuffer = 101L,
			depth = ImageBinding(301L, 302L, 303),
			hudless = ImageBinding(401L, 402L, 403),
			ui = ImageBinding(501L, 502L, 503),
		)

		// Not ready yet: the tag must not reach the bridge.
		assertFalse(adapter.tagFgResources(request), "a session that is not READY must not tag")
		assertEquals(0, native.tagFgResourcesCalls)

		// Ready: initialize arms the session, and the request crosses untouched.
		assertTrue(
			adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")) != null,
			"initialize must bring the session to READY",
		)
		assertTrue(adapter.tagFgResources(request), "a READY session must tag the frame's resources")
		assertEquals(listOf(request), native.tagFgResourcesRequests)

		// A refused tag latches the session under the frame's resource-tag stage, exactly like
		// the SR tag and any other native stage.
		native.tagFgResourcesResult = NativeApi.SUCCESS_RESULT + 1
		assertFalse(adapter.tagFgResources(request), "a refused FG tag must latch the session")
		assertEquals(DlssSessionState.FALLBACK_LATCHED, session.state)
		assertEquals(DlssNativeStage.TAG, session.failure?.stage)
	}

	/**
	 * Asserts the rung's validation oracle was actually running, then that the Khronos
	 * validation layer reported no errors naming any of the frame's four tagged images.
	 *
	 * Validation is the only oracle for image layouts: a declared state that disagreed with
	 * the images' actual layout is undefined behaviour to the driver and silent without it,
	 * so a session whose layer could not be enabled has no evidence worth asserting on and
	 * the rung FAILS rather than silently skipping the clean check.
	 */
	private fun assertFgTagValidationClean(
		fixture: HeadlessVulkanFixture,
		depth: HeadlessVulkanFixture.EngineImage,
		hudless: HeadlessVulkanFixture.EngineImage,
		ui: HeadlessVulkanFixture.EngineImage,
		images: DlssEvaluationImages,
	) {
		assertTrue(
			fixture.validationEnabled(),
			"the rung needs the Khronos validation layer (VK_LAYER_KHRONOS_validation " +
				"plus VK_EXT_debug_utils); without it the clean-frame assertion is worthless",
		)
		val errors = fixture.validationErrorsAbout(
			depth.image(),
			hudless.image(),
			ui.image(),
			images.motion.image,
		)
		assertTrue(
			errors.isEmpty(),
			"the tagged frame must not leave a resource in a state validation rejects: $errors",
		)
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

	private fun config(outputDimensions: DlssDimensions) = DlssStartupConfig(
		enabled = true,
		qualityMode = SRMode.QUALITY,
		outputDimensions = outputDimensions,
		sdkPath = null,
		nativeLibraryPath = null,
		dataPath = null,
		warnings = emptyList(),
	)

	/**
	 * A request with all three engine images present and in the formats the FG options
	 * recorded - D32_SFLOAT depth, R8G8B8A8_UNORM HUD-less and UI - on a non-zero command
	 * buffer. The lifecycle checks (options recorded, images acquired) are what this request
	 * passes through or is refused by.
	 */
	private fun validTagRequest() = FgTagRequest(
		commandBuffer = 1L,
		depth = ImageBinding(301L, 302L, VK10.VK_FORMAT_D32_SFLOAT),
		hudless = ImageBinding(401L, 402L, VK10.VK_FORMAT_R8G8B8A8_UNORM),
		ui = ImageBinding(501L, 502L, VK10.VK_FORMAT_R8G8B8A8_UNORM),
	)

	/**
	 * Records the FG-tag seam and answers the three calls [LifecycleAdapter.initialize]
	 * drives; everything else is a call this test never makes.
	 */
	private class FakeNative : NativeApi {
		var tagFgResourcesResult = NativeApi.SUCCESS_RESULT
		var tagFgResourcesCalls = 0
		val tagFgResourcesRequests = mutableListOf<FgTagRequest>()

		override fun tagFgResources(request: FgTagRequest): Int {
			tagFgResourcesCalls++
			tagFgResourcesRequests += request
			return tagFgResourcesResult
		}

		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		): Int = NativeApi.SUCCESS_RESULT

		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int) =
			DlssDimensions(1280, 720)

		override fun configure(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		): Int = NativeApi.SUCCESS_RESULT

		override fun acquireImages(): DlssEvaluationImages = error("unexpected acquireImages")
		override fun releaseImages(): Int = error("unexpected releaseImages")
		override fun waitDeviceIdle(): Int = error("unexpected waitDeviceIdle")
		override fun frameTimings(): DlssFrameTimings? = error("unexpected frameTimings")
		override fun writeMotion(request: MotionRequest): Int = error("unexpected writeMotion")
		override fun presentOutput(target: PresentTarget): Int = error("unexpected presentOutput")
		override fun evaluate(request: EvaluationRequest): Int = error("unexpected evaluate")
	}

	private companion object {
		/** NVSDK_NGX_Result_FAIL_NotInitialized = NVSDK_NGX_Result_Fail | 7 (0xBAD00000 | 7). */
		const val FAIL_NOT_INITIALIZED = 0xBAD00007.toInt()

		/** NVSDK_NGX_Result_FAIL_InvalidParameter = NVSDK_NGX_Result_Fail | 5 (0xBAD00000 | 5). */
		const val FAIL_INVALID_PARAMETER = 0xBAD00005.toInt()
	}
}
