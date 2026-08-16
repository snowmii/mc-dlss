package me.snowmii.dlss.fg

import java.nio.file.Path
import me.snowmii.dlss.NativeBridge
import me.snowmii.streamline.CameraConstants
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.streamline.ExtensionBootstrap
import me.snowmii.streamline.FgTagRequest
import me.snowmii.dlss.bridge.HeadlessVulkanFixture
import me.snowmii.streamline.ImageBinding
import me.snowmii.streamline.Native
import me.snowmii.streamline.NativeApi
import me.snowmii.streamline.NativeException
import me.snowmii.streamline.SrTagRequest
import me.snowmii.streamline.Vec2

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
@NativeBridge
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
							srOnlyFrame.address(),
							ImageBinding(
								color.view(),
								color.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							),
							ImageBinding(
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
						EvaluationRequest.builder()
							.commandBuffer(srOnlyFrame.address())
							.color(ImageBinding(
								color.view(),
								color.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							))
							.depth(ImageBinding(
								depth.view(),
								depth.image(),
								VK10.VK_FORMAT_D32_SFLOAT,
							))
							.jitter(Vec2(0.25f, -0.5f))
							.motionScale(Vec2(1f, 1f))
							.frameTimeMilliseconds(16.6f)
							.resetHistory(true)
							.renderDimensions(dimensions)
							.camera(TEST_CAMERA)
							.build(),
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
							composedFrame.address(),
							ImageBinding(
								depth.view(),
								depth.image(),
								VK10.VK_FORMAT_D32_SFLOAT,
							),
							ImageBinding(
								hudless.view(),
								hudless.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							),
							ImageBinding(
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
							composedFrame.address(),
							ImageBinding(
								color.view(),
								color.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							),
							ImageBinding(
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
						EvaluationRequest.builder()
							.commandBuffer(composedFrame.address())
							.color(ImageBinding(
								color.view(),
								color.image(),
								VK10.VK_FORMAT_R8G8B8A8_UNORM,
							))
							.depth(ImageBinding(
								depth.view(),
								depth.image(),
								VK10.VK_FORMAT_D32_SFLOAT,
							))
							.jitter(Vec2(0.25f, -0.5f))
							.motionScale(Vec2(1f, 1f))
							.frameTimeMilliseconds(16.6f)
							.resetHistory(false)
							.renderDimensions(dimensions)
							.camera(STEPPED_CAMERA)
							.build(),
					),
					"the composed evaluation must record the frame's constants on both viewports",
				)

				// Both oracles answer the composed frame's camera, each from its own record:
				// the SR record on viewport 0, raw; the FG record on the FG-only viewport,
				// carrying the FG viewport's orientation - one flip per matrix role, because
				// the ABI's matrices are row-vector (v' = v · M): the viewToClip's flip lands
				// on the output side (M · F), the clipToView's on the input side (F · M⁻¹),
				// and the reprojection pair conjugates (F · M · F); the jitter's y negates -
				// because the FG tag names y-flipped copies while SR stays in engine space.
				// The SR record must answer exactly the camera the caller handed in,
				// flip-free: the orientation split must never reach what SR reads.
				val srRecorded = bridge.queryCameraConstants()
				assertCameraEquals(STEPPED_CAMERA, srRecorded)
				assertEquals(0.25f, srRecorded.jitterX, "the SR record must carry the raw jitter x")
				assertEquals(-0.5f, srRecorded.jitterY, "the SR record must carry the raw jitter y")
				val fgRecorded = bridge.queryFgCameraConstants()
				assertCameraEquals(flippedForBackbuffer(STEPPED_CAMERA), fgRecorded)
				assertEquals(0.25f, fgRecorded.jitterX, "the FG record must keep the jitter x")
				assertEquals(0.5f, fgRecorded.jitterY, "the FG record must negate the jitter y")

				// The FG record's matrices must behave like their roles, not like a generic
				// conjugation: the viewToClip maps a camera-space row vector to the raw
				// projection's clip with the y negated, the clipToView is its exact
				// row-vector inverse, and the reprojection pair conjugates - a flipped
				// prev-clip vector lands on the flipped clip the raw pair produces.
				val probe = floatArrayOf(3f, 2f, -7f, 1f)
				val fgClip = matMulVec4(probe, fgRecorded.viewToClip)
				assertFlippedVec4(
					matMulVec4(probe, srRecorded.viewToClip),
					fgClip,
					"the FG viewToClip must negate the raw projection's clip y",
				)
				for (c in 0 until 4) {
					assertEquals(
						probe[c],
						matMulVec4(fgClip, fgRecorded.clipToView)[c],
						1e-3f,
						"the FG clipToView must invert the FG viewToClip (component $c)",
					)
				}
				assertTrue(
					matMul(fgRecorded.clipToPrevClip, fgRecorded.prevClipToClip).isIdentity(),
					"the FG prevClipToClip must be the exact row-vector inverse of the FG clipToPrevClip",
				)
				val prevProbe = floatArrayOf(0.2f, -0.4f, 0.5f, 1f)
				assertFlippedVec4(
					matMulVec4(prevProbe, srRecorded.clipToPrevClip),
					matMulVec4(flipVec4Y(prevProbe), fgRecorded.clipToPrevClip),
					"the FG clipToPrevClip must map a flipped prev clip to the flipped clip",
				)

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

	/**
	 * The FG viewport's record of a camera, under the FG viewport's backbuffer orientation.
	 * The ABI's matrices are row-vector (v' = v · M), so where a matrix maps from and to
	 * decides the flip: the viewToClip maps a camera-space row vector into the flipped clip
	 * space, so the flip lands on the output side - M' = M · F, column 1 negated, flat
	 * indices 1, 5, 9, 13; the clipToView maps a flipped clip-space row vector back into
	 * camera space, so the flip lands on the input side - M' = F · M⁻¹, row 1 negated,
	 * flat indices 4, 5, 6, 7; the reprojection pair maps flipped clip to flipped clip and
	 * conjugates - F · M · F, indices 1, 4, 6, 7, 9, 13 (index 5 twice, unchanged). The
	 * asymmetry is what keeps the pair exact row-vector inverses: (M · F) · (F · M⁻¹) = I.
	 * The jitter's y negates, and everything else - the world-space position and basis and
	 * the frustum scalars - is orientation-free and carries unchanged.
	 */
	private fun flippedForBackbuffer(camera: CameraConstants): CameraConstants = CameraConstants(
		columnYFlipped(camera.viewToClip),
		rowYFlipped(camera.clipToView),
		camera.pos,
		camera.right,
		camera.up,
		camera.fwd,
		conjugatedY(camera.clipToPrevClip),
		conjugatedY(camera.prevClipToClip),
		camera.near,
		camera.far,
		camera.fovRadians,
		camera.aspectRatio,
		camera.jitterX,
		-camera.jitterY,
	)

	/** Output-side y flip M' = M · F: column 1 negated - flat indices 1, 5, 9, 13. */
	private fun columnYFlipped(matrix: FloatArray): FloatArray =
		matrix.copyOf().also { flipped ->
			for (index in COLUMN_Y_INDICES) {
				flipped[index] = -flipped[index]
			}
		}

	/** Input-side y flip M' = F · M: row 1 negated - flat indices 4, 5, 6, 7. */
	private fun rowYFlipped(matrix: FloatArray): FloatArray =
		matrix.copyOf().also { flipped ->
			for (index in ROW_Y_INDICES) {
				flipped[index] = -flipped[index]
			}
		}

	/** Conjugation M' = F · M · F: row 1 and column 1 negated, [1][1] twice - unchanged. */
	private fun conjugatedY(matrix: FloatArray): FloatArray =
		matrix.copyOf().also { flipped ->
			for (index in FLIPPED_Y_INDICES) {
				flipped[index] = -flipped[index]
			}
		}

	/** Row-vector · matrix product c = v · M. */
	private fun matMulVec4(v: FloatArray, m: FloatArray): FloatArray {
		val out = FloatArray(4)
		for (c in 0 until 4) {
			var sum = 0f
			for (k in 0 until 4) {
				sum += v[k] * m[k * 4 + c]
			}
			out[c] = sum
		}
		return out
	}

	/** Row-vector 4x4 product c = a · b, both row-major. */
	private fun matMul(a: FloatArray, b: FloatArray): FloatArray {
		val out = FloatArray(16)
		for (r in 0 until 4) {
			for (c in 0 until 4) {
				var sum = 0f
				for (k in 0 until 4) {
					sum += a[r * 4 + k] * b[k * 4 + c]
				}
				out[r * 4 + c] = sum
			}
		}
		return out
	}

	/** True when the 4x4 equals the identity within the float product tolerance. */
	private fun FloatArray.isIdentity(): Boolean {
		for (r in 0 until 4) {
			for (c in 0 until 4) {
				val expected = if (r == c) 1f else 0f
				if (Math.abs(this[r * 4 + c] - expected) > 1e-3f) {
					return false
				}
			}
		}
		return true
	}

	/** Asserts [actual] equals [expected] with the y component (1) negated. */
	private fun assertFlippedVec4(expected: FloatArray, actual: FloatArray, message: String) {
		for (c in 0 until 4) {
			val sign = if (c == 1) -1f else 1f
			assertEquals(expected[c] * sign, actual[c], 1e-3f, "$message (component $c)")
		}
	}

	/** Negates the y component (1) of a 4-vector. */
	private fun flipVec4Y(v: FloatArray): FloatArray = floatArrayOf(v[0], -v[1], v[2], v[3])

	private companion object {
		/** The flat indices M · F negates: column 1 of a row-major 4x4. */
		private val COLUMN_Y_INDICES = intArrayOf(1, 5, 9, 13)

		/** The flat indices F · M negates: row 1 of a row-major 4x4. */
		private val ROW_Y_INDICES = intArrayOf(4, 5, 6, 7)

		/** The flat indices F · M · F negates: row 1 and column 1, the [1][1] element twice. */
		private val FLIPPED_Y_INDICES = intArrayOf(1, 4, 6, 7, 9, 13)

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
				CameraConstants.rowMajorOf(projection),
				CameraConstants.rowMajorOf(Matrix4f(projection).invert()),
				floatArrayOf(12f, 64f, -48f),
				right,
				up,
				fwd,
				CameraConstants.rowMajorOf(step),
				CameraConstants.rowMajorOf(Matrix4f(step).invert()),
				0.05f,
				1000f,
				Math.toRadians(70.0).toFloat(),
				16f / 9f,
			)
		}

		/**
		 * The composed frame's camera: the same basis as [TEST_CAMERA] with a different far
		 * plane, so the two records are distinguishable - an oracle answering the previous
		 * frame's far plane would fail the equality check.
		 */
		val STEPPED_CAMERA: CameraConstants = CameraConstants(
			TEST_CAMERA.viewToClip,
			TEST_CAMERA.clipToView,
			TEST_CAMERA.pos,
			TEST_CAMERA.right,
			TEST_CAMERA.up,
			TEST_CAMERA.fwd,
			TEST_CAMERA.clipToPrevClip,
			TEST_CAMERA.prevClipToClip,
			TEST_CAMERA.near,
			5000f,
			TEST_CAMERA.fovRadians,
			TEST_CAMERA.aspectRatio,
			TEST_CAMERA.jitterX,
			TEST_CAMERA.jitterY,
		)

		/** sl::Result::eErrorNotInitialized, the oracles' pre-record refusal. */
		private const val FAIL_NOT_INITIALIZED = 0xBAD00007.toInt()
	}
}
