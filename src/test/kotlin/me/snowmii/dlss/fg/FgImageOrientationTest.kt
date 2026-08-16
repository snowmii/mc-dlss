package me.snowmii.dlss.fg

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import me.snowmii.dlss.NativeBridge
import me.snowmii.dlss.bridge.CameraConstants
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.bridge.EvaluationRequest
import me.snowmii.dlss.bridge.ExtensionBootstrap
import me.snowmii.dlss.bridge.FgTagRequest
import me.snowmii.dlss.bridge.FillVelocityRequest
import me.snowmii.dlss.bridge.HeadlessVulkanFixture
import me.snowmii.dlss.bridge.ImageBinding
import me.snowmii.dlss.bridge.MotionRequest
import me.snowmii.dlss.bridge.Native
import me.snowmii.dlss.bridge.NativeApi
import me.snowmii.dlss.bridge.NativeException
import me.snowmii.dlss.bridge.SrTagRequest
import me.snowmii.dlss.bridge.Vec2
import me.snowmii.dlss.bridge.rowMajorOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.joml.Matrix4f
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VkBufferCreateInfo
import org.lwjgl.vulkan.VkBufferImageCopy
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkDeviceCreateInfo
import org.lwjgl.vulkan.VkImageMemoryBarrier
import org.lwjgl.vulkan.VkInstance
import org.lwjgl.vulkan.VkInstanceCreateInfo
import org.lwjgl.vulkan.VkMemoryAllocateInfo
import org.lwjgl.vulkan.VkMemoryRequirements
import org.lwjgl.vulkan.VkPhysicalDevice
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties

/**
 * M-11.5 FG buffer orientation rung (AC-2): the composed frame's FG tag names module-owned
 * backbuffer-oriented copies - the engine's depth, HUD-less, and UI content mirrored about
 * the horizontal axis, and the motion field mirrored with its y component negated - while
 * the FG viewport's constants carry the matching flip and the SR viewport stays raw.
 *
 * DLSS-G interpolates the swapchain image, which Minecraft's present blit mirrors vertically
 * relative to every engine texture, so a tag that names the engine's images describes the
 * frame upside down. This rung proves the fix through the public boundary: the owned copies
 * are read back through mc_dlss_query_fg_images and compared against known asymmetric
 * engine content - a per-row pattern no mirror can leave unchanged - and the two constants
 * oracles are compared against the caller's known camera: the SR oracle raw, the FG oracle
 * with one flip per matrix role - the ABI's matrices are row-vector (v' = v · M), so the
 * viewToClip carries the output-side flip M · F, the clipToView the input-side flip
 * F · M⁻¹ (the pair stays exact inverses), and the reprojection pair the conjugation
 * F · M · F - and the jitter's y negated. A half-fix - flipped matrices without flipped
 * images, or one of the four buffers left in engine space - fails these proofs loudly.
 *
 * Both motion routes are pinned: the camera-only writer fills the flipped motion copy from
 * its depth reconstruction, and the velocity-MRT fill does the same for a field whose every
 * pixel is genuine object motion, so the mirror-and-negate write is proven for the two
 * producers the invariant names.
 *
 * Like the other live FG rungs the scenario runs in ONE test method (one test fork): the
 * close-path slShutdown is what makes the fork's exit clean.
 */
@NativeBridge
class FgImageOrientationTest {

	@Test
	fun `one live session tags mirrored FG copies and flips only the FG constants`(
		@TempDir dataPath: Path,
	) {
		// Pre-init: this fork's module has never bootstrapped and never evaluated, so the FG
		// constants oracle has nothing to answer with and the FG images query has no session.
		Native.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
			assertNullFgConstants(bridge)
			assertEquals(
				FAIL_NOT_INITIALIZED,
				bridge.fgImagesResultOrThrow(),
				"the FG images query before any session must answer FAIL_NotInitialized",
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

				// The owned FG copies: the handles the orientation proof reads back through.
				val fgImages = bridge.queryFgImages()
				assertTrue(fgImages.depth().image != 0L, "the flipped depth copy must exist")
				assertTrue(fgImages.hudless().image != 0L, "the flipped HUD-less copy must exist")
				assertTrue(fgImages.ui().image != 0L, "the flipped UI copy must exist")
				assertTrue(fgImages.motion().image != 0L, "the flipped motion copy must exist")
				assertEquals(VK10.VK_FORMAT_D32_SFLOAT, fgImages.depth().format)
				assertEquals(VK10.VK_FORMAT_R8G8B8A8_UNORM, fgImages.hudless().format)
				assertEquals(VK10.VK_FORMAT_R8G8B8A8_UNORM, fgImages.ui().format)
				assertEquals(VK10.VK_FORMAT_R16G16_SFLOAT, fgImages.motion().format)

				// The engine's render-sized colour and depth, its output-sized HUD-less and
				// UI targets, and the velocity companion for the MRT route's frame.
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
				val velocity = fixture.createEngineImage(
					dimensions.width,
					dimensions.height,
					VK10.VK_FORMAT_R16G16_SFLOAT,
					VK10.VK_IMAGE_USAGE_SAMPLED_BIT or VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
					VK10.VK_IMAGE_ASPECT_COLOR_BIT,
				)

				// Asymmetric per-row content, so a vertical mirror changes the image and a
				// uniform fill could never mask a missing flip. Depth is per-row fp32 values,
				// the two colours per-row RGBA bytes, the velocity companion per-row fp16
				// vectors (all far from the invalid sentinel).
				stageIntoImage(fixture, depth.image(), dimensions.width, dimensions.height,
					VK10.VK_IMAGE_ASPECT_DEPTH_BIT, 4) { bytes ->
					val floats = bytes.order(ByteOrder.nativeOrder()).asFloatBuffer()
					for (row in 0 until dimensions.height) {
						val value = depthOfRow(row)
						repeat(dimensions.width) {
							floats.put(value)
						}
					}
				}
				stageIntoImage(fixture, hudless.image(), outputWidth, outputHeight,
					VK10.VK_IMAGE_ASPECT_COLOR_BIT, 4) { bytes ->
					for (row in 0 until outputHeight) {
						repeat(outputWidth) {
							bytes.put((row % 256).toByte())
							bytes.put(((row / 256) % 256).toByte())
							bytes.put((255 - row % 256).toByte())
							bytes.put(128.toByte())
						}
					}
				}
				stageIntoImage(fixture, ui.image(), outputWidth, outputHeight,
					VK10.VK_IMAGE_ASPECT_COLOR_BIT, 4) { bytes ->
					for (row in 0 until outputHeight) {
						repeat(outputWidth) {
							bytes.put((255 - row % 256).toByte())
							bytes.put((row % 256).toByte())
							bytes.put(64.toByte())
							bytes.put(128.toByte())
						}
					}
				}
				stageIntoImage(fixture, velocity.image(), dimensions.width, dimensions.height,
					VK10.VK_IMAGE_ASPECT_COLOR_BIT, 4) { bytes ->
					val halves = bytes.order(ByteOrder.nativeOrder()).asShortBuffer()
					for (row in 0 until dimensions.height) {
						val x = toHalf(velocityXOfRow(row))
						val y = toHalf(velocityYOfRow(row))
						repeat(dimensions.width) {
							halves.put(x).put(y)
						}
					}
				}

				// The patterns must actually differ between the top and bottom rows: a
				// uniform source would make every mirror check vacuous.
				assertTrue(depthOfRow(0) != depthOfRow(dimensions.height - 1))
				assertTrue(velocityXOfRow(0) != velocityXOfRow(dimensions.height - 1))

				// A reprojection that moves every pixel differently by row: a scale plus
				// translation in NDC, so the motion field depends on the pixel's ndc y.
				val reprojection = FloatArray(16).also {
					Matrix4f().translation(0.05f, -0.03f, 0f).scale(1.2f, 0.8f, 1f).get(it)
				}

				// Frame 1, camera-only route: the motion dispatch reconstructs the field
				// from depth, and both the engine-space motion image and the flipped copy are
				// written by the one dispatch.
				val frame1 = fixture.allocateAndBeginCommandBuffer()
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.writeMotion(
						MotionRequest(
							commandBuffer = frame1.address(),
							depth = ImageBinding(
								depth.view(),
								depth.image(),
								VK10.VK_FORMAT_D32_SFLOAT,
							),
							reprojection = reprojection,
							renderDimensions = dimensions,
						),
					),
					"the camera-only motion pass must record",
				)
				composeFrame(bridge, frame1, depth, hudless, ui, color, dimensions, reset = true)
				fixture.endSubmitAndWait(frame1)

				// The owned copies hold the engine's content mirrored about the horizontal
				// axis: every pixel matches the engine's pixel at the mirrored row.
				assertMirrored(
					readDepthImage(fixture, depth.image(), dimensions.width, dimensions.height),
					readDepthImage(fixture, fgImages.depth().image, dimensions.width, dimensions.height),
					dimensions.width, dimensions.height, 1, 0f, "the flipped depth copy",
				)
				assertMirrored(
					fixture.readRgba8Image(hudless.image(), outputWidth, outputHeight),
					fixture.readRgba8Image(fgImages.hudless().image, outputWidth, outputHeight),
					outputWidth, outputHeight, 4, UNORM_TOLERANCE, "the flipped HUD-less copy",
				)
				assertMirrored(
					fixture.readRgba8Image(ui.image(), outputWidth, outputHeight),
					fixture.readRgba8Image(fgImages.ui().image, outputWidth, outputHeight),
					outputWidth, outputHeight, 4, UNORM_TOLERANCE, "the flipped UI copy",
				)
				// The motion copy is the engine's field mirrored with the y component
				// negated: a mirrored image encodes mirrored motion, so the blit flip alone
				// would hand the plugin a vector that points the wrong way up.
				val engineMotion1 = fixture.readRg16fImage(
					images.motion.image, dimensions.width, dimensions.height,
				)
				val fgMotion1 = fixture.readRg16fImage(
					fgImages.motion().image, dimensions.width, dimensions.height,
				)
				assertMotionMirroredAndNegated(
					engineMotion1, fgMotion1, dimensions.width, dimensions.height,
					"the camera-only flipped motion copy",
				)

				// The constants: the SR oracle reports the caller's camera raw - the
				// orientation split must never reach what SR reads - and the FG oracle
				// reports the FG viewport's record: one flip per matrix role (the
				// viewToClip's flip on the output side M · F, the clipToView's on the input
				// side F · M⁻¹, the reprojection pair's conjugation F · M · F) and the
				// jitter's y negated, everything else unchanged.
				val srRecorded = bridge.queryCameraConstants()
				assertCameraEquals(TEST_CAMERA, srRecorded)
				assertEquals(0.25f, srRecorded.jitterX, "the SR record must carry the raw jitter x")
				assertEquals(-0.5f, srRecorded.jitterY, "the SR record must carry the raw jitter y")
				val fgRecorded = bridge.queryFgCameraConstants()
				assertCameraEquals(flippedForBackbuffer(TEST_CAMERA), fgRecorded)
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

				// Frame 2, velocity-MRT route: the companion's per-row object vectors are
				// copied unchanged into the engine-space motion image, and the same
				// mirror-and-negate write fills the FG copy - pinning the flip for the
				// object-motion path too, not just the depth reconstruction.
				val frame2 = fixture.allocateAndBeginCommandBuffer()
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.fillVelocity(
						FillVelocityRequest(
							commandBuffer = frame2.address(),
							depth = ImageBinding(
								depth.view(),
								depth.image(),
								VK10.VK_FORMAT_D32_SFLOAT,
							),
							velocity = ImageBinding(
								velocity.view(),
								velocity.image(),
								VK10.VK_FORMAT_R16G16_SFLOAT,
							),
							reprojection = reprojection,
							reset = false,
							renderDimensions = dimensions,
						),
					),
					"the velocity-MRT fill must record",
				)
				composeFrame(bridge, frame2, depth, hudless, ui, color, dimensions, reset = false)
				fixture.endSubmitAndWait(frame2)

				val engineMotion2 = fixture.readRg16fImage(
					images.motion.image, dimensions.width, dimensions.height,
				)
				val fgMotion2 = fixture.readRg16fImage(
					fgImages.motion().image, dimensions.width, dimensions.height,
				)
				assertMotionMirroredAndNegated(
					engineMotion2, fgMotion2, dimensions.width, dimensions.height,
					"the velocity-fill flipped motion copy",
				)
				// The frame 2 field really is the companion's own vectors: object motion is
				// preserved on the engine-space side, which is what makes the mirror check
				// above a proof of the fill's flipped write rather than of a field that never
				// existed.
				assertEquals(
					velocityXOfRow(0),
					engineMotion2[2],
					"the engine-space motion field must carry the companion's row-0 x at pixel (1, 0)",
				)
				assertEquals(
					velocityYOfRow(0),
					engineMotion2[3],
					"the engine-space motion field must carry the companion's row-0 y at pixel (1, 0)",
				)
			}
		}
	}

	/**
	 * Records one composed frame's remaining calls on [frame]: the FG tag first (obtaining
	 * the retained frame token and recording the orientation copies), then the SR tag, the
	 * evaluation with the known camera and jitter, the second FG tag (the post-evaluation
	 * re-declaration), and the handoff with its present bracket.
	 */
	private fun composeFrame(
		bridge: Native,
		frame: org.lwjgl.vulkan.VkCommandBuffer,
		depth: HeadlessVulkanFixture.EngineImage,
		hudless: HeadlessVulkanFixture.EngineImage,
		ui: HeadlessVulkanFixture.EngineImage,
		color: HeadlessVulkanFixture.EngineImage,
		dimensions: DlssDimensions,
		reset: Boolean,
	) {
		assertEquals(
			NativeApi.SUCCESS_RESULT,
			bridge.tagFgResources(
				FgTagRequest(
					commandBuffer = frame.address(),
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
			"the FG tag must record first and obtain the retained frame token",
		)
		assertEquals(
			NativeApi.SUCCESS_RESULT,
			bridge.tagSrResources(
				SrTagRequest(
					commandBuffer = frame.address(),
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
			"the SR tag must record under the FG tag's retained frame token",
		)
		assertEquals(
			NativeApi.SUCCESS_RESULT,
			bridge.evaluate(
				EvaluationRequest(
					commandBuffer = frame.address(),
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
					resetHistory = reset,
					renderDimensions = dimensions,
					camera = TEST_CAMERA,
				),
			),
			"the evaluation must record the frame's constants on both viewports",
		)
		assertEquals(
			NativeApi.SUCCESS_RESULT,
			bridge.tagFgResources(
				FgTagRequest(
					commandBuffer = frame.address(),
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
			"the second FG tag must re-declare the copies under the retained token",
		)
		assertEquals(
			NativeApi.SUCCESS_RESULT,
			bridge.presentHandoff(),
			"the handoff must accept the complete tag set",
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

	/**
	 * Asserts `mirrored` is `engine` flipped about the horizontal axis: mirrored row y must
	 * equal engine row (height - 1 - y), component by component.
	 */
	private fun assertMirrored(
		engine: FloatArray,
		mirrored: FloatArray,
		width: Int,
		height: Int,
		channels: Int,
		tolerance: Float,
		label: String,
	) {
		assertEquals(width * height * channels, engine.size, "$label: engine readback size")
		assertEquals(engine.size, mirrored.size, "$label: mirrored readback size")
		for (row in 0 until height) {
			val mirroredRow = height - 1 - row
			for (x in 0 until width) {
				for (c in 0 until channels) {
					assertEquals(
						engine[(row * width + x) * channels + c],
						mirrored[(mirroredRow * width + x) * channels + c],
						tolerance,
						"$label must mirror pixel ($x, $row) from engine row $mirroredRow",
					)
				}
			}
		}
	}

	/**
	 * Asserts `flipped` is the motion field mirrored about the horizontal axis with the y
	 * component negated: a mirrored image encodes mirrored motion, so the blit flip alone
	 * would hand the plugin a vector that points the wrong way up.
	 */
	private fun assertMotionMirroredAndNegated(
		engine: FloatArray,
		flipped: FloatArray,
		width: Int,
		height: Int,
		label: String,
	) {
		assertEquals(width * height * 2, engine.size, "$label: engine motion readback size")
		assertEquals(engine.size, flipped.size, "$label: flipped motion readback size")
		for (row in 0 until height) {
			val mirroredRow = height - 1 - row
			for (x in 0 until width) {
				val engineIndex = (row * width + x) * 2
				val flippedIndex = (mirroredRow * width + x) * 2
				assertEquals(
					engine[engineIndex],
					flipped[flippedIndex],
					0f,
					"$label must keep the motion x at the mirrored pixel ($x, $row)",
				)
				assertEquals(
					-engine[engineIndex + 1],
					flipped[flippedIndex + 1],
					0f,
					"$label must negate the motion y at the mirrored pixel ($x, $row)",
				)
			}
		}
	}

	private fun assertCameraEquals(expected: CameraConstants, actual: CameraConstants) {
		assertTrue(
			expected.viewToClip.contentEquals(actual.viewToClip),
			"viewToClip must match the expected record",
		)
		assertTrue(
			expected.clipToView.contentEquals(actual.clipToView),
			"clipToView must match the expected record",
		)
		assertTrue(expected.pos.contentEquals(actual.pos), "cameraPos must match the expected record")
		assertTrue(
			expected.right.contentEquals(actual.right),
			"cameraRight must match the expected record",
		)
		assertTrue(
			expected.up.contentEquals(actual.up),
			"cameraUp must match the expected record",
		)
		assertTrue(
			expected.fwd.contentEquals(actual.fwd),
			"cameraFwd must match the expected record",
		)
		assertTrue(
			expected.clipToPrevClip.contentEquals(actual.clipToPrevClip),
			"clipToPrevClip must match the expected record",
		)
		assertTrue(
			expected.prevClipToClip.contentEquals(actual.prevClipToClip),
			"prevClipToClip must match the expected record",
		)
		assertEquals(expected.near, actual.near, "cameraNear must match the expected record")
		assertEquals(expected.far, actual.far, "cameraFar must match the expected record")
		assertEquals(expected.fovRadians, actual.fovRadians, "cameraFOV must match the expected record")
		assertEquals(
			expected.aspectRatio,
			actual.aspectRatio,
			"cameraAspectRatio must match the expected record",
		)
	}

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
	private fun flippedForBackbuffer(camera: CameraConstants): CameraConstants = camera.copy(
		viewToClip = columnYFlipped(camera.viewToClip),
		clipToView = rowYFlipped(camera.clipToView),
		clipToPrevClip = conjugatedY(camera.clipToPrevClip),
		prevClipToClip = conjugatedY(camera.prevClipToClip),
		jitterX = camera.jitterX,
		jitterY = -camera.jitterY,
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

	/**
	 * Reads the FG constants oracle through the ABI, answering null for the
	 * FAIL_NotInitialized refusal - the state every pre-evaluation checkpoint asserts.
	 */
	private fun assertNullFgConstants(bridge: Native) {
		try {
			bridge.queryFgCameraConstants()
			error("the FG camera-constants oracle before any evaluation must refuse")
		} catch (error: NativeException) {
			assertEquals(FAIL_NOT_INITIALIZED, error.resultCode(), "the FG oracle must refuse before any evaluation")
		}
	}

	/** The FG images query's result, throwing on success so pre-init refusals are checkable. */
	private fun Native.fgImagesResultOrThrow(): Int = try {
		queryFgImages()
		error("the FG images query before any session must refuse")
	} catch (error: NativeException) {
		error.resultCode()
	}

	/** One per-row depth value: distinct by row, inside the reversed-Z unit range. */
	private fun depthOfRow(row: Int): Float = 0.2f + row * 0.0004f

	/** One per-row velocity x: a multiple of 0.25, exactly representable in fp16. */
	private fun velocityXOfRow(row: Int): Float = row * 0.25f

	/** One per-row velocity y: a multiple of 0.125, exactly representable in fp16. */
	private fun velocityYOfRow(row: Int): Float = 0.5f + row * 0.125f

	/**
	 * Encodes one float as a half-float bit pattern, for the pattern values this test writes
	 * (finite, in the fp16 normal range - exactly representable by construction).
	 */
	private fun toHalf(value: Float): Short {
		val bits = value.toBits()
		val sign = (bits ushr 16) and 0x8000
		val exponent = (bits ushr 23) and 0xFF
		val mantissa = bits and 0x7FFFFF
		val shifted = exponent - 127 + 15
		return when {
			exponent == 0xFF -> (sign or 0x7C00 or (if (mantissa != 0) 0x200 else 0)).toShort()
			shifted <= 0 -> sign.toShort()
			shifted >= 31 -> (sign or 0x7C00).toShort()
			else -> (sign or (shifted shl 10) or (mantissa ushr 13)).toShort()
		}
	}

	/**
	 * Reads back a D32_SFLOAT depth image as one float per pixel, in row-major order: the
	 * same staging-buffer round trip as the fixture's colour readbacks, with the depth aspect
	 * the copy names. Taken from VK_IMAGE_LAYOUT_GENERAL and handed straight back to it.
	 */
	private fun readDepthImage(
		fixture: HeadlessVulkanFixture,
		image: Long,
		width: Int,
		height: Int,
	): FloatArray {
		MemoryStack.stackPush().use { stack ->
			val device = vulkanDevice(fixture, stack)
			val byteCount = (width.toLong() * height * 4)
			val bufferInfo = VkBufferCreateInfo.calloc(stack).`sType$Default`()
				.size(byteCount)
				.usage(VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT)
				.sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
			val bufferPtr = stack.callocLong(1)
			checkVk(VK10.vkCreateBuffer(device, bufferInfo, null, bufferPtr), "vkCreateBuffer")
			val buffer = bufferPtr.get(0)

			val requirements = VkMemoryRequirements.calloc(stack)
			VK10.vkGetBufferMemoryRequirements(device, buffer, requirements)
			val allocateInfo = VkMemoryAllocateInfo.calloc(stack).`sType$Default`()
				.allocationSize(requirements.size())
				.memoryTypeIndex(hostVisibleMemoryType(fixture, stack, requirements.memoryTypeBits()))
			val memoryPtr = stack.callocLong(1)
			checkVk(VK10.vkAllocateMemory(device, allocateInfo, null, memoryPtr), "vkAllocateMemory")
			val memory = memoryPtr.get(0)
			checkVk(VK10.vkBindBufferMemory(device, buffer, memory, 0), "vkBindBufferMemory")

			val commandBuffer = fixture.allocateAndBeginCommandBuffer()
			recordLayoutTransition(commandBuffer, image, VK10.VK_IMAGE_ASPECT_DEPTH_BIT, VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
			val region = VkBufferImageCopy.calloc(1, stack)
			region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
			region.get(0).imageSubresource().set(VK10.VK_IMAGE_ASPECT_DEPTH_BIT, 0, 0, 1)
			region.get(0).imageOffset().set(0, 0, 0)
			region.get(0).imageExtent().set(width, height, 1)
			VK10.vkCmdCopyImageToBuffer(
				commandBuffer,
				image,
				VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
				buffer,
				region,
			)
			recordLayoutTransition(commandBuffer, image, VK10.VK_IMAGE_ASPECT_DEPTH_BIT, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK10.VK_IMAGE_LAYOUT_GENERAL)
			fixture.endSubmitAndWait(commandBuffer)

			val mapped = stack.callocPointer(1)
			checkVk(
				VK10.vkMapMemory(device, memory, 0, byteCount, 0, mapped),
				"vkMapMemory",
			)
			val floats = MemoryUtil.memByteBuffer(mapped.get(0), byteCount.toInt())
				.order(ByteOrder.nativeOrder())
				.asFloatBuffer()
			val values = FloatArray(width * height)
			floats.get(values)
			VK10.vkUnmapMemory(device, memory)
			VK10.vkDestroyBuffer(device, buffer, null)
			VK10.vkFreeMemory(device, memory, null)
			return values
		}
	}

	/**
	 * Uploads a caller-built byte pattern into [image] through a host-visible staging buffer,
	 * on the fixture's own recording: transition GENERAL -> TRANSFER_DST, copy, and back, so
	 * the image rests in GENERAL exactly as Minecraft rests its own.
	 */
	private fun stageIntoImage(
		fixture: HeadlessVulkanFixture,
		image: Long,
		width: Int,
		height: Int,
		aspect: Int,
		bytesPerPixel: Int,
		fill: (ByteBuffer) -> Unit,
	) {
		MemoryStack.stackPush().use { stack ->
			val device = vulkanDevice(fixture, stack)
			val byteCount = (width.toLong() * height * bytesPerPixel)
			val bufferInfo = VkBufferCreateInfo.calloc(stack).`sType$Default`()
				.size(byteCount)
				.usage(VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
				.sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
			val bufferPtr = stack.callocLong(1)
			checkVk(VK10.vkCreateBuffer(device, bufferInfo, null, bufferPtr), "vkCreateBuffer")
			val buffer = bufferPtr.get(0)

			val requirements = VkMemoryRequirements.calloc(stack)
			VK10.vkGetBufferMemoryRequirements(device, buffer, requirements)
			val allocateInfo = VkMemoryAllocateInfo.calloc(stack).`sType$Default`()
				.allocationSize(requirements.size())
				.memoryTypeIndex(hostVisibleMemoryType(fixture, stack, requirements.memoryTypeBits()))
			val memoryPtr = stack.callocLong(1)
			checkVk(VK10.vkAllocateMemory(device, allocateInfo, null, memoryPtr), "vkAllocateMemory")
			val memory = memoryPtr.get(0)
			checkVk(VK10.vkBindBufferMemory(device, buffer, memory, 0), "vkBindBufferMemory")

			val mapped = stack.callocPointer(1)
			checkVk(
				VK10.vkMapMemory(device, memory, 0, byteCount, 0, mapped),
				"vkMapMemory",
			)
			fill(MemoryUtil.memByteBuffer(mapped.get(0), byteCount.toInt()))
			VK10.vkUnmapMemory(device, memory)

			val commandBuffer = fixture.allocateAndBeginCommandBuffer()
			recordLayoutTransition(commandBuffer, image, aspect, VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
			val region = VkBufferImageCopy.calloc(1, stack)
			region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
			region.get(0).imageSubresource().set(aspect, 0, 0, 1)
			region.get(0).imageOffset().set(0, 0, 0)
			region.get(0).imageExtent().set(width, height, 1)
			VK10.vkCmdCopyBufferToImage(
				commandBuffer,
				buffer,
				image,
				VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
				region,
			)
			recordLayoutTransition(commandBuffer, image, aspect, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK10.VK_IMAGE_LAYOUT_GENERAL)
			fixture.endSubmitAndWait(commandBuffer)

			VK10.vkDestroyBuffer(device, buffer, null)
			VK10.vkFreeMemory(device, memory, null)
		}
	}

	/** Records one image layout transition with the broad masks the module itself uses. */
	private fun recordLayoutTransition(
		commandBuffer: org.lwjgl.vulkan.VkCommandBuffer,
		image: Long,
		aspect: Int,
		oldLayout: Int,
		newLayout: Int,
	) {
		MemoryStack.stackPush().use { stack ->
			val barrier = VkImageMemoryBarrier.calloc(1, stack).`sType$Default`()
				.srcAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT or VK10.VK_ACCESS_MEMORY_WRITE_BIT)
				.dstAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT or VK10.VK_ACCESS_MEMORY_WRITE_BIT)
				.oldLayout(oldLayout)
				.newLayout(newLayout)
				.srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
				.dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
				.image(image)
			barrier.get(0).subresourceRange().set(aspect, 0, 1, 0, 1)
			VK10.vkCmdPipelineBarrier(
				commandBuffer,
				VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
				VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
				0,
				null,
				null,
				barrier,
			)
		}
	}

	private fun hostVisibleMemoryType(fixture: HeadlessVulkanFixture, stack: MemoryStack, typeBits: Int): Int {
		val instance = VkInstance(fixture.instanceAddress(), VkInstanceCreateInfo.calloc(stack))
		val physicalDevice = VkPhysicalDevice(fixture.physicalDeviceAddress(), instance)
		val properties = VkPhysicalDeviceMemoryProperties.calloc(stack)
		VK10.vkGetPhysicalDeviceMemoryProperties(physicalDevice, properties)
		val required = VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
		for (candidate in 0 until properties.memoryTypeCount()) {
			val allowed = (typeBits and (1 shl candidate)) != 0
			val flags = properties.memoryTypes(candidate).propertyFlags()
			if (allowed && (flags and required) == required) {
				return candidate
			}
		}
		error("no host-visible coherent memory type for typeBits $typeBits")
	}

	/** The fixture's device wrapped for LWJGL calls, rebuilt per helper for a fresh stack. */
	private fun vulkanDevice(fixture: HeadlessVulkanFixture, stack: MemoryStack): VkDevice {
		val instance = VkInstance(fixture.instanceAddress(), VkInstanceCreateInfo.calloc(stack))
		val physicalDevice = VkPhysicalDevice(fixture.physicalDeviceAddress(), instance)
		return VkDevice(fixture.deviceAddress(), physicalDevice, VkDeviceCreateInfo.calloc(stack))
	}

	private fun checkVk(result: Int, call: String) {
		assertEquals(VK10.VK_SUCCESS, result, "$call must succeed")
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
		/** The flat indices M · F negates: column 1 of a row-major 4x4. */
		private val COLUMN_Y_INDICES = intArrayOf(1, 5, 9, 13)

		/** The flat indices F · M negates: row 1 of a row-major 4x4. */
		private val ROW_Y_INDICES = intArrayOf(4, 5, 6, 7)

		/** The flat indices F · M · F negates: row 1 and column 1, the [1][1] element twice. */
		private val FLIPPED_Y_INDICES = intArrayOf(1, 4, 6, 7, 9, 13)

		/** UNORM readback tolerance: one and a half 8-bit quanta, for the blit's linear filter. */
		private const val UNORM_TOLERANCE = 0.006f

		/** sl::Result::eErrorNotInitialized, the oracles' pre-record refusal. */
		private const val FAIL_NOT_INITIALIZED = 0xBAD00007.toInt()

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
	}
}
