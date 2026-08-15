package me.snowmii.dlss.fg

import java.nio.file.Path
import me.snowmii.dlss.bridge.CameraConstants
import me.snowmii.dlss.bridge.EvaluationRequest
import me.snowmii.dlss.bridge.ExtensionBootstrap
import me.snowmii.dlss.bridge.FgTagRequest
import me.snowmii.dlss.bridge.HeadlessVulkanFixture
import me.snowmii.dlss.bridge.ImageBinding
import me.snowmii.dlss.bridge.Native
import me.snowmii.dlss.bridge.NativeApi
import me.snowmii.dlss.bridge.NativeException
import me.snowmii.dlss.bridge.SrTagRequest
import me.snowmii.dlss.bridge.Vec2
import me.snowmii.dlss.bridge.rowMajorOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.joml.Matrix4f
import org.lwjgl.vulkan.VK10

/**
 * M-11.5 FG viewport split rung: every FG-side options, state, tag, and constants record
 * names one FG-only Streamline viewport while SR records stay on viewport 0, and a composed
 * frame's FG constants are observable independently of the SR record under the shared frame
 * token.
 *
 * The split is the required precursor to the y-orientation fix: only after the FG side owns
 * a viewport of its own can a later slice flip its images/matrices without touching what SR
 * reads. This rung proves the split through behavior: the FG constants oracle answers only
 * for composed frames - an SR-only evaluation establishes the SR record and never the FG
 * one - and the composed frame's two constants records keep the retained frame token the
 * tags obtained, which the handoff acceptance and the SR/FG tag index equality make
 * observable.
 *
 * Like the other live FG rungs the scenario runs in ONE test method (one test fork): the
 * close-path slShutdown is what makes the fork's exit clean.
 */
class FgViewportSplitTest {

	@Test
	fun `one live session records FG constants only for composed frames and keeps the shared token`(
		@TempDir dataPath: Path,
	) {
		// Pre-init: this fork's module has never bootstrapped and never evaluated, so both
		// constants oracles have nothing to answer with.
		Native.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
			assertNull(
				bridge.srCameraConstantsOrNull(),
				"the SR camera-constants oracle before any evaluation must have no record",
			)
			assertNull(
				bridge.fgCameraConstantsOrNull(),
				"the FG camera-constants oracle before any evaluation must have no record",
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
				// MaxQuality = 2 (NVSDK_NGX_PerfQuality_Value); preset K = 11.
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
				// The FG options record on the FG viewport is what the FG state reads, tag,
				// and input wait all gate on: recording it here is what makes the FG-side
				// records below provable as one coherent viewport.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.configureFg(3),
					"the FG options must record on the FG viewport",
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

				// First an SR-only frame: the SR tag obtains the token, the evaluation records
				// the camera on the SR viewport only, and the token is consumed. This is the
				// independence half of the invariant: a frame with no FG side must not
				// establish the FG constants record.
				val srOnlyFrame = fixture.allocateAndBeginCommandBuffer()
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagSrResources(
						SrTagRequest(
							commandBuffer = srOnlyFrame.address(),
							color = ImageBinding(
								color.view(),
								color.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							),
							depth = ImageBinding(
								depth.view(),
								depth.image(),
								VK10.VK_FORMAT_D32_SFLOAT,
							),
						),
					),
					"the SR tag must record on the SR viewport",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.evaluate(
						EvaluationRequest(
							commandBuffer = srOnlyFrame.address(),
							color = ImageBinding(
								color.view(),
								color.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							),
							depth = ImageBinding(
								depth.view(),
								depth.image(),
								VK10.VK_FORMAT_D32_SFLOAT,
							),
							jitter = Vec2(0.25f, -0.5f),
							motionScale = Vec2(1f, 1f),
							frameTimeMilliseconds = 16.6f,
							resetHistory = true,
							renderDimensions = dimensions,
							camera = TEST_CAMERA,
						),
					),
					"the SR-only evaluation must record the frame's constants",
				)
				assertCameraEquals(TEST_CAMERA, bridge.queryCameraConstants())
				assertNull(
					bridge.fgCameraConstantsOrNull(),
					"an SR-only evaluation must not establish the FG constants record",
				)

				// Then a composed frame: the FG tag records first and obtains the retained
				// token, the SR tag records second under that same token, and the evaluation
				// records the camera on both viewports - the FG record on the FG-only
				// viewport, the SR record on viewport 0.
				val composedFrame = fixture.allocateAndBeginCommandBuffer()
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagFgResources(
						FgTagRequest(
							commandBuffer = composedFrame.address(),
							depth = ImageBinding(
								depth.view(),
								depth.image(),
								VK10.VK_FORMAT_D32_SFLOAT,
							),
							hudless = ImageBinding(
								hudless.view(),
								hudless.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							),
							ui = ImageBinding(
								ui.view(),
								ui.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							),
						),
					),
					"the FG tag must record first on the FG viewport and obtain the retained frame token",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagSrResources(
						SrTagRequest(
							commandBuffer = composedFrame.address(),
							color = ImageBinding(
								color.view(),
								color.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							),
							depth = ImageBinding(
								depth.view(),
								depth.image(),
								VK10.VK_FORMAT_D32_SFLOAT,
							),
						),
					),
					"the SR tag must record on the same buffer under the FG tag's retained frame token",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.evaluate(
						EvaluationRequest(
							commandBuffer = composedFrame.address(),
							color = ImageBinding(
								color.view(),
								color.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							),
							depth = ImageBinding(
								depth.view(),
								depth.image(),
								VK10.VK_FORMAT_D32_SFLOAT,
							),
							jitter = Vec2(0.25f, -0.5f),
							motionScale = Vec2(1f, 1f),
							frameTimeMilliseconds = 16.6f,
							resetHistory = false,
							renderDimensions = dimensions,
							camera = STEPPED_CAMERA,
						),
					),
					"the composed evaluation must record the frame's constants on both viewports",
				)

				// Both oracles answer the composed frame's camera, each from its own record:
				// the SR record on viewport 0, the FG record on the FG-only viewport.
				assertCameraEquals(STEPPED_CAMERA, bridge.queryCameraConstants())
				assertCameraEquals(STEPPED_CAMERA, bridge.queryFgCameraConstants())

				// The FG constants record used the shared retained token rather than
				// obtaining (or consuming) one of its own: the two tags stay on one frame
				// index, and the token survives the constants records into the handoff.
				val indexes = bridge.taggedFrameIndexes()
				assertEquals(
					indexes.srFrameIndex,
					indexes.fgFrameIndex,
					"the FG constants record must keep the frame token the tags share",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.presentHandoff(),
					"the handoff must still find the retained token after both constants records",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.presentStart(),
					"the armed bracket's START must emit under the retained token",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.presentEnd(),
					"the armed bracket's END must emit and consume the retained token",
				)
			}
		}
	}

	private fun assertCameraEquals(expected: CameraConstants, actual: CameraConstants) {
		assertTrue(
			expected.viewToClip.contentEquals(actual.viewToClip),
			"viewToClip must reach the constants record unchanged",
		)
		assertTrue(
			expected.clipToView.contentEquals(actual.clipToView),
			"clipToView must reach the constants record unchanged",
		)
		assertTrue(expected.pos.contentEquals(actual.pos), "cameraPos must reach the constants record unchanged")
		assertTrue(
			expected.right.contentEquals(actual.right),
			"cameraRight must reach the constants record unchanged",
		)
		assertTrue(
			expected.up.contentEquals(actual.up),
			"cameraUp must reach the constants record unchanged",
		)
		assertTrue(
			expected.fwd.contentEquals(actual.fwd),
			"cameraFwd must reach the constants record unchanged",
		)
		assertTrue(
			expected.clipToPrevClip.contentEquals(actual.clipToPrevClip),
			"clipToPrevClip must reach the constants record unchanged",
		)
		assertTrue(
			expected.prevClipToClip.contentEquals(actual.prevClipToClip),
			"prevClipToClip must reach the constants record unchanged",
		)
		assertEquals(expected.near, actual.near, "cameraNear must reach the constants record unchanged")
		assertEquals(expected.far, actual.far, "cameraFar must reach the constants record unchanged")
		assertEquals(expected.fovRadians, actual.fovRadians, "cameraFOV must reach the constants record unchanged")
		assertEquals(
			expected.aspectRatio,
			actual.aspectRatio,
			"cameraAspectRatio must reach the constants record unchanged",
		)
	}

	/**
	 * Reads the SR constants oracle through the ABI, answering null for the
	 * FAIL_NotInitialized refusal - the state every pre-evaluation checkpoint asserts.
	 */
	private fun Native.srCameraConstantsOrNull(): CameraConstants? = try {
		queryCameraConstants()
	} catch (error: NativeException) {
		if (error.resultCode() == FAIL_NOT_INITIALIZED) {
			null
		} else {
			throw error
		}
	}

	/**
	 * Reads the FG constants oracle through the ABI, answering null for the
	 * FAIL_NotInitialized refusal - the state that proves an SR-only frame established no
	 * FG record and a fresh fork has none to report.
	 */
	private fun Native.fgCameraConstantsOrNull(): CameraConstants? = try {
		queryFgCameraConstants()
	} catch (error: NativeException) {
		if (error.resultCode() == FAIL_NOT_INITIALIZED) {
			null
		} else {
			throw error
		}
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
		/**
		 * The frame's standing-in camera, built like a real Minecraft camera: a perspective
		 * view-to-clip projection and the orthonormal right/up/forward basis of a yaw 37° /
		 * pitch 12° camera. fwd is the view vector at that yaw/pitch, right its yaw-only
		 * horizontal companion, and up = right x fwd completes the right-handed frame.
		 */
		val TEST_CAMERA: CameraConstants = run {
			val yaw = Math.toRadians(37.0)
			val pitch = Math.toRadians(12.0)
			val (sinY, cosY) = Math.sin(yaw) to Math.cos(yaw)
			val (sinP, cosP) = Math.sin(pitch) to Math.cos(pitch)
			val fwd = floatArrayOf(
				(-sinY * cosP).toFloat(),
				(-sinP).toFloat(),
				(cosY * cosP).toFloat(),
			)
			val right = floatArrayOf((-cosY).toFloat(), 0f, (-sinY).toFloat())
			val up = floatArrayOf(
				right[1] * fwd[2] - right[2] * fwd[1],
				right[2] * fwd[0] - right[0] * fwd[2],
				right[0] * fwd[1] - right[1] * fwd[0],
			)
			val projection = Matrix4f().perspective(
				Math.toRadians(70.0).toFloat(),
				16f / 9f,
				0.05f,
				1000f,
				true, // Vulkan zero-to-one depth, like Minecraft 26.2's backend
			)
			val step = Matrix4f().translation(0.03f, -0.02f, 0.01f).rotateY(0.05f)
			CameraConstants(
				viewToClip = rowMajorOf(projection),
				clipToView = rowMajorOf(Matrix4f(projection).invert()),
				pos = floatArrayOf(12f, 64f, -48f),
				right = right,
				up = up,
				fwd = fwd,
				clipToPrevClip = rowMajorOf(step),
				prevClipToClip = rowMajorOf(Matrix4f(step).invert()),
				near = 0.05f,
				far = 1000f,
				fovRadians = Math.toRadians(70.0).toFloat(),
				aspectRatio = 16f / 9f,
			)
		}

		/**
		 * The composed frame's camera: the same basis as [TEST_CAMERA] with a different far
		 * plane, so the two records are distinguishable - an oracle answering the previous
		 * frame's far plane would fail the equality check.
		 */
		val STEPPED_CAMERA: CameraConstants = TEST_CAMERA.copy(far = 5000f)

		/** sl::Result::eErrorNotInitialized, the oracles' pre-record refusal. */
		private const val FAIL_NOT_INITIALIZED = 0xBAD00007.toInt()
	}
}
