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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.joml.Matrix4f
import org.lwjgl.vulkan.VK10

/**
 * M-11 camera-constants rung: the evaluation's single `slSetConstants` carries the real
 * camera matrices and orthonormal basis the caller handed through the ABI, unchanged, and
 * the native oracle `mc_dlss_query_camera_constants` reads exactly those constants back.
 *
 * The human probe isolated the missing camera constants as the last cause of a stuck
 * input-processing fence: identity constants advanced the fence while zero constants left it
 * at zero, which means the DLSS-G plugin needs the frame's real camera to interpolate the
 * generated frame across. The present-generation rung observes the plugin accepting those
 * constants (fence advance, presentation factor); this rung proves the *values*: the exact
 * 44 floats the frame recorded reach the plugin, and the basis they form is orthonormal -
 * the property the plugin's auto scene-change detection verifies before it runs, and the
 * reason a zero-filled or degenerate basis leaves generation disabled.
 *
 * The seam is the oracle, and the oracle refuses before any evaluation recorded constants,
 * which is what makes the pre-evaluation state observable: a fresh fork's module (and a
 * ready session that only recorded options and tags) answers not-initialized, and only a
 * successful `slSetConstants` establishes the record.
 *
 * The frame is composed exactly like the production present path ([FgPresentMarkersTest]):
 * the FG tag records first and obtains the retained frame token, the SR tag records second
 * under that same token, and the SR evaluation records third - the common-constants path,
 * where the camera lands under the token - with no handoff or present needed for the oracle
 * to answer. Like the other live FG rungs, the whole scenario runs in ONE test method (and
 * therefore one test fork): the close-path slShutdown is what makes the fork's exit clean.
 */
@NativeBridge
class FgCameraConstantsTest {

	@Test
	fun `rowMajorOf carries JOML's payload to the ABI without transposing it`() {
		val m = Matrix4f()
		m.set(
			1f, 2f, 3f, 4f,
			5f, 6f, 7f, 8f,
			9f, 10f, 11f, 12f,
			13f, 14f, 15f, 16f,
		)
		// JOML and Streamline differ in both storage order and vector convention, and the two
		// differences cancel: the same element lands at the same flat index on both sides, so
		// the ABI payload is JOML's get() array verbatim. Transposing it is what left the
		// DLSS-G input fence at zero for every non-symmetric matrix.
		val expected = floatArrayOf(
			1f, 2f, 3f, 4f,
			5f, 6f, 7f, 8f,
			9f, 10f, 11f, 12f,
			13f, 14f, 15f, 16f,
		)
		assertTrue(
			CameraConstants.rowMajorOf(m).contentEquals(expected),
			"rowMajorOf must pass the get() payload through unchanged",
		)
	}

	@Test
	fun `a perspective projection keeps its w term at the index Streamline reads it from`() {
		// The regression this whole slice exists for: under Streamline's row-vector convention
		// the perspective w = -z term lives at flat index 11, exactly where JOML's
		// column-vector column-major array already puts it. The old transpose moved it to
		// index 14 - the translation slot - which is why the identity fixture generated frames
		// and Minecraft's real projection did not.
		// 70 degrees in radians: Minecraft's default field of view.
		val projection = Matrix4f().perspective(1.2217305f, 16f / 9f, 0.05f, 1000f)
		val payload = CameraConstants.rowMajorOf(projection)
		assertEquals(-1f, payload[11], "the perspective w term must sit at flat index 11")
		// Index 14 carries the depth translation term. The old transpose swapped 11 and 14, so
		// finding the w term here is the exact shape of the bug this rung fixes.
		assertTrue(payload[14] != -1f, "index 14 must carry depth translation, not the w term")
	}

	@Test
	fun `one live session records the real camera into slSetConstants and the oracle reads it back`(
		@TempDir dataPath: Path,
	) {
		// Pre-init: this fork's module has never bootstrapped and never evaluated, so the
		// oracle has nothing to answer with - the pre-evaluation half of the invariant,
		// checked on a throwaway bridge against the module's own state.
		Native.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
			assertEquals(
				null,
				bridge.cameraConstantsOrNull(),
				"the camera-constants oracle before any evaluation must have no record",
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
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.configureFg(3),
					"the FG options must record for the stored configuration",
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

				// A ready session that only recorded options, tags, and nothing else still has
				// no constants record: only a successful slSetConstants establishes one, so
				// the oracle must refuse between the tags and the evaluation too.
				assertEquals(
					null,
					bridge.cameraConstantsOrNull(),
					"recording options and tags must not record camera constants",
				)

				// The engine's render-sized colour and depth and its output-sized HUD-less and
				// UI targets, standing in for Minecraft's main target, depth texture, and the
				// split's two targets.
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

				val frame = fixture.allocateAndBeginCommandBuffer()

				// One frame, one buffer, one shared frame token, composed exactly like the
				// production present path: the FG tag records first and obtains the retained
				// token, the SR tag records second under that same token, and the SR evaluation
				// records third - the common-constants path, where the camera lands under the
				// token the tags recorded under.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagFgResources(
						FgTagRequest(
							frame.address(),
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
					"the FG tag must record first and obtain the retained frame token",
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.tagSrResources(
						SrTagRequest(
							frame.address(),
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
							.commandBuffer(frame.address())
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
					"the SR evaluation must record the frame's constants on the tagged frame's shared buffer",
				)

				// The oracle answers exactly what the evaluation carried: every matrix and
				// basis float the caller handed in reached slSetConstants unchanged, and the
				// basis is orthonormal - the property the DLSS-G plugin's auto scene-change
				// detection verifies before it runs.
				val recorded = bridge.queryCameraConstants()
				assertCameraEquals(TEST_CAMERA, recorded)
				assertOrthonormal(recorded)
			}
		}
	}

	private fun assertCameraEquals(expected: CameraConstants, actual: CameraConstants) {
		assertTrue(
			expected.viewToClip.contentEquals(actual.viewToClip),
			"viewToClip must reach slSetConstants unchanged",
		)
		assertTrue(
			expected.clipToView.contentEquals(actual.clipToView),
			"clipToView must reach slSetConstants unchanged",
		)
		assertTrue(expected.pos.contentEquals(actual.pos), "cameraPos must reach slSetConstants unchanged")
		assertTrue(
			expected.right.contentEquals(actual.right),
			"cameraRight must reach slSetConstants unchanged",
		)
		assertTrue(
			expected.up.contentEquals(actual.up),
			"cameraUp must reach slSetConstants unchanged",
		)
		assertTrue(
			expected.fwd.contentEquals(actual.fwd),
			"cameraFwd must reach slSetConstants unchanged",
		)
		assertTrue(
			expected.clipToPrevClip.contentEquals(actual.clipToPrevClip),
			"clipToPrevClip must reach slSetConstants unchanged",
		)
		assertTrue(
			expected.prevClipToClip.contentEquals(actual.prevClipToClip),
			"prevClipToClip must reach slSetConstants unchanged",
		)
		assertEquals(expected.near, actual.near, "cameraNear must reach slSetConstants unchanged")
		assertEquals(expected.far, actual.far, "cameraFar must reach slSetConstants unchanged")
		assertEquals(expected.fovRadians, actual.fovRadians, "cameraFOV must reach slSetConstants unchanged")
		assertEquals(
			expected.aspectRatio,
			actual.aspectRatio,
			"cameraAspectRatio must reach slSetConstants unchanged",
		)
	}

	/**
	 * The basis the DLSS-G plugin requires: right, up, and fwd are unit vectors, mutually
	 * perpendicular, and form a right-handed camera frame (fwd = up x right). The plugin's
	 * auto scene-change detection stitches them into a rotation matrix and verifies the
	 * orthonormal property before it runs; a degenerate basis disables it entirely.
	 */
	private fun assertOrthonormal(camera: CameraConstants) {
		val right = camera.right
		val up = camera.up
		val fwd = camera.fwd
		assertEquals(1f, length(right), 1e-5f, "cameraRight must be unit length")
		assertEquals(1f, length(up), 1e-5f, "cameraUp must be unit length")
		assertEquals(1f, length(fwd), 1e-5f, "cameraFwd must be unit length")
		assertEquals(0f, dot(right, up), 1e-5f, "cameraRight must be perpendicular to cameraUp")
		assertEquals(0f, dot(right, fwd), 1e-5f, "cameraRight must be perpendicular to cameraFwd")
		assertEquals(0f, dot(up, fwd), 1e-5f, "cameraUp must be perpendicular to cameraFwd")
		val upCrossRight = floatArrayOf(
			up[1] * right[2] - up[2] * right[1],
			up[2] * right[0] - up[0] * right[2],
			up[0] * right[1] - up[1] * right[0],
		)
		assertTrue(
			fwd.contentEquals(upCrossRight),
			"the basis must be right-handed: fwd = up x right",
		)
	}

	private fun length(v: FloatArray): Float = kotlin.math.sqrt(dot(v, v))

	private fun dot(a: FloatArray, b: FloatArray): Float =
		a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

	/**
	 * Reads the oracle through the ABI, answering null for the FAIL_NotInitialized refusal -
	 * the state every pre-evaluation checkpoint asserts.
	 */
	private fun Native.cameraConstantsOrNull(): CameraConstants? = try {
		queryCameraConstants()
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
			// A real camera step, so the clip-to-prev-clip pair is not the identity: those two
			// matrices and the four frustum scalars are the non-optional sl::Constants fields
			// that reached the plugin as INVALID_FLOAT until the mod wrote them.
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

		/** sl::Result::eErrorNotInitialized, the oracle's pre-evaluation refusal. */
		private const val FAIL_NOT_INITIALIZED = 0xBAD00007.toInt()
	}
}
