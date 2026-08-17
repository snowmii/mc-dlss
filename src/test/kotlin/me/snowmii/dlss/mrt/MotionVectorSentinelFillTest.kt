package me.snowmii.dlss.mrt

import me.snowmii.dlss.NativeBridge
import me.snowmii.dlss.render.*
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.session.SRMode
import me.snowmii.dlss.sl.SrLiveSession
import me.snowmii.streamline.*
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VkCommandBuffer
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.max

/**
 * The sentinel-fill gate: on the velocity-MRT route one post-scene compute dispatch
 * samples the scene depth and Minecraft's sparse RG16_FLOAT velocity companion, copies every
 * non-sentinel object vector unchanged into the module's native motion image, reconstructs
 * jitter-stripped camera motion for every sentinel pixel, and writes the invalid sentinel
 * everywhere on a reset frame. The fill lands before the tag on the same buffer, the native
 * motion image is the sole Streamline motion source (direct companion tagging is retired),
 * and the route's motion timing stage is a real measured stage, never a skipped one.
 *
 * The frame-routing half is pure JVM like the other route tests: the frame path runs
 * against a recording [NativeApi] fake. The native half is one live headless Vulkan session on
 * the shared SL test seam: real fills record on real command buffers under the Khronos
 * validation layer, and the module's motion image is read back through a staging buffer so the
 * merge semantics are asserted as executed pixels - non-sentinel vectors copied, sentinels
 * reconstructed, reset frames blanked - not as source text. The five camera-only writers,
 * their mixins, and their tests are untouched; the CAMERA_ONLY route is asserted only to prove
 * the fill never rides it.
 */
@NativeBridge
class MotionVectorSentinelFillTest {
	@Test
	fun `the velocity route fills the native motion image from the sampled companion before tagging`() {
		val calls = RecordingNativeApi(RENDER_DIMENSIONS)
		val evaluation = evaluation(calls)
		val velocity = ImageBinding(11L, 12L, 124)

		assertTrue(
			evaluation.evaluateFrame(
				scene(),
				jitter(),
				motion(),
				route = MotionVectorRoute.VELOCITY_MRT,
				velocity = velocity,
			),
			"the velocity-route frame must record through to the evaluation",
		)
		val fill = calls.fills.single()
		assertEquals(velocity, fill.velocity, "the fill must sample the scene velocity companion")
		assertEquals(scene().depth, fill.depth, "the fill must read the scene depth image")
		val expectedReprojection = FloatArray(16).also { motion().reprojection.get(it) }
		assertTrue(
			expectedReprojection.contentEquals(fill.reprojection),
			"the fill must carry the jitter-stripped reprojection",
		)
		assertFalse(fill.reset, "a continuous frame must not carry the reset flag")
		assertEquals(
			listOf("fill", "tag", "evaluate"),
			calls.order,
			"the fill must precede the tag in the frame's recording",
		)
		assertTrue(calls.writeMotion.isEmpty(), "the velocity route must not record the compute camera-motion writer")
		// Direct companion tagging is retired: the tag request carries only the engine colour
		// and depth, and the native side always tags the module's motion image as the motion
		// source. The destination of the fill never crosses the ABI back in.
		assertEquals(scene().color, calls.tags.single().color)
		assertEquals(scene().depth, calls.tags.single().depth)
		assertEquals(1, calls.evaluations.size, "the frame must still evaluate")
	}

	@Test
	fun `the fill carries the reset flag of the frame's motion`() {
		val calls = RecordingNativeApi(RENDER_DIMENSIONS)
		val evaluation = evaluation(calls)

		// A reset frame has no valid predecessor: the native fill must write the invalid
		// sentinel everywhere rather than reconstruct camera motion, and the flag that decides
		// that rides the fill request.
		assertTrue(
			evaluation.evaluateFrame(
				scene(),
				jitter(),
				motion(reset = true),
				route = MotionVectorRoute.VELOCITY_MRT,
				velocity = ImageBinding(11L, 12L, 124),
			),
		)
		assertTrue(calls.fills.single().reset, "a reset frame must carry the reset flag to the fill")
	}

	@Test
	fun `the camera-only route records no fill`() {
		val calls = RecordingNativeApi(RENDER_DIMENSIONS)
		val evaluation = evaluation(calls)

		assertTrue(evaluation.evaluateFrame(scene(), jitter(), motion()))
		assertTrue(calls.fills.isEmpty(), "the camera-only route must keep the compute writer and no fill")
		assertEquals(1, calls.writeMotion.size)
	}

	@Test
	fun `a velocity route without a companion records nothing`() {
		val calls = RecordingNativeApi(RENDER_DIMENSIONS)
		val evaluation = evaluation(calls)

		assertFalse(evaluation.evaluateFrame(scene(), jitter(), motion(), route = MotionVectorRoute.VELOCITY_MRT))
		assertTrue(calls.fills.isEmpty())
		assertTrue(calls.tags.isEmpty())
		assertTrue(calls.evaluations.isEmpty())
	}

	@Test
	fun `the adapter stamps the configured render dimensions onto the fill`() {
		val calls = RecordingNativeApi(RENDER_DIMENSIONS)
		val evaluation = evaluation(calls)

		assertTrue(
			evaluation.evaluateFrame(
				scene(),
				jitter(),
				motion(),
				route = MotionVectorRoute.VELOCITY_MRT,
				velocity = ImageBinding(11L, 12L, 124),
			),
		)
		assertEquals(
			RENDER_DIMENSIONS,
			calls.fills.single().renderDimensions,
			"the adapter must stamp the configured render size onto the fill, like every recording call",
		)
	}

	/**
	 * The native half of the gate, on the shared headless validated SL seam: a real session
	 * records real fills on real command buffers, and the module's motion image is read back
	 * through a staging buffer so the merge semantics are asserted as executed pixels. The
	 * velocity companion is created without storage usage - exactly like Minecraft's - so a
	 * fill that bound it as storage would fail validation, and every frame must submit clean.
	 */
	@Test
	fun `the live merge preserves object vectors, reconstructs sentinels, resets, syncs the companion, tags, and times`(
		@TempDir dataPath: Path,
	) {
		SrLiveSession.withLiveSession(dataPath) { bridge, fixture ->
			val renderWidth = 1280
			val renderHeight = 720
			val outputWidth = 2560
			val outputHeight = 1440
			val renderDimensions = Dimensions(renderWidth, renderHeight)
			val outputDimensions = Dimensions(outputWidth, outputHeight)

			assertEquals(
				NativeApi.SUCCESS_RESULT,
				bridge.configureSuperResolution(outputWidth, outputHeight, renderWidth, renderHeight, 2, 11),
				"configure must record the render size the fill frames are recorded for",
			)

			// The engine's render-sized images, standing in for Minecraft's main target, depth
			// texture, and velocity companion, plus the output-sized present target. The
			// companion carries no storage usage, and the fill frames leave it in GENERAL like
			// the engine's renderers do.
			val color = fixture.createEngineImage(
				renderWidth, renderHeight, VK10.VK_FORMAT_R8G8B8A8_UNORM,
				VK10.VK_IMAGE_USAGE_SAMPLED_BIT or VK10.VK_IMAGE_USAGE_STORAGE_BIT,
				VK10.VK_IMAGE_ASPECT_COLOR_BIT,
			)
			val depth = fixture.createEngineImage(
				renderWidth, renderHeight, VK10.VK_FORMAT_D32_SFLOAT,
				VK10.VK_IMAGE_USAGE_SAMPLED_BIT or VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT or
					VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT,
				VK10.VK_IMAGE_ASPECT_DEPTH_BIT,
			)
			val velocity = fixture.createEngineImage(
				renderWidth, renderHeight, VK10.VK_FORMAT_R16G16_SFLOAT,
				VK10.VK_IMAGE_USAGE_SAMPLED_BIT or VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or
					VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT,
				VK10.VK_IMAGE_ASPECT_COLOR_BIT,
			)
			val presentTarget = fixture.createEngineImage(
				outputWidth, outputHeight, VK10.VK_FORMAT_R8G8B8A8_UNORM,
				VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT,
				VK10.VK_IMAGE_ASPECT_COLOR_BIT,
			)
			val velocityBinding =
				ImageBinding(velocity.view(), velocity.image(), VK10.VK_FORMAT_R16G16_SFLOAT)

			// The fill's destination is the module's own motion image, so nothing merges until
			// it exists at the configured size - the same gate as the compute writer.
			val beforeImages = fixture.allocateAndBeginCommandBuffer()
			assertTrue(
				bridge.fillVelocity(
					FillVelocityRequest(
						beforeImages.address(),
						ImageBinding(depth.view(), depth.image(), VK10.VK_FORMAT_D32_SFLOAT),
						velocityBinding,
						FloatArray(16),
						false,
						renderDimensions,
					),
				) != NativeApi.SUCCESS_RESULT,
				"the fill must fail before the module's images exist at the configured size",
			)

			val images = bridge.acquireImages()
			assertNotNull(images, "module images must be acquired before the fill")
			val motionImage = images!!.motion

			// Column-major clip-space scale by two: previous = (2*ndc, 0, 1), so a reconstructed
			// pixel's motion is exactly its NDC position - per-pixel varying, and distinguishable
			// from both the copied vector and the sentinel.
			val scaleReprojection = FloatArray(16).also {
				it[0] = 2.0f
				it[5] = 2.0f
				it[15] = 1.0f
			}
			fun fill(commandBuffer: Long, reprojection: FloatArray, reset: Boolean) = FillVelocityRequest(
				commandBuffer,
				ImageBinding(depth.view(), depth.image(), VK10.VK_FORMAT_D32_SFLOAT),
				velocityBinding,
				reprojection,
				reset,
				renderDimensions,
			)

			// Frame one - the copy branch: the companion carries a real object vector at every
			// pixel, written as a color clear on the same command buffer the fill records on.
			// The readback can only see the vector if the fill's explicit barrier ordered the
			// scene's color-attachment writes ahead of its sampled reads.
			val copyFrame = fixture.allocateAndBeginCommandBuffer()
			fixture.recordColorClear(copyFrame, velocity.image(), OBJECT_VECTOR_X, OBJECT_VECTOR_Y, 0f, 0f)
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				bridge.fillVelocity(fill(copyFrame.address(), scaleReprojection, reset = false)),
				"the merge must record on the caller's command buffer",
			)
			fixture.endSubmitAndWait(copyFrame)
			val copied = fixture.readRg16fImage(motionImage.image, renderWidth, renderHeight)
			var worstCopyDeviation = 0.0f
			for (index in copied.indices) {
				val expected = if (index % 2 == 0) OBJECT_VECTOR_X else OBJECT_VECTOR_Y
				worstCopyDeviation = max(worstCopyDeviation, abs(copied[index] - expected))
			}
			assertTrue(
				worstCopyDeviation < 0.01f,
				"every non-sentinel companion vector must be copied unchanged into the motion image, " +
					"worst deviation $worstCopyDeviation",
			)

			// Frame two - the reconstruction branch: the companion is entirely sentinel and the
			// depth is a known constant, so every pixel's motion must be the reprojected camera
			// motion for its pixel centre - the NDC difference through the scale matrix.
			val reconstructFrame = fixture.allocateAndBeginCommandBuffer()
			fixture.recordColorClear(reconstructFrame, velocity.image(), INVALID_VELOCITY, INVALID_VELOCITY, 0f, 0f)
			fixture.recordDepthClear(reconstructFrame, depth, 0.5f)
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				bridge.fillVelocity(fill(reconstructFrame.address(), scaleReprojection, reset = false)),
				"the merge must reconstruct camera motion for sentinel pixels",
			)
			fixture.endSubmitAndWait(reconstructFrame)
			val reconstructed = fixture.readRg16fImage(motionImage.image, renderWidth, renderHeight)
			var worstReconstructionDeviation = 0.0f
			for (y in 0 until renderHeight) {
				for (x in 0 until renderWidth) {
					val ndcX = ((x + 0.5f) / renderWidth) * 2.0f - 1.0f
					val ndcY = ((y + 0.5f) / renderHeight) * 2.0f - 1.0f
					worstReconstructionDeviation = max(
						worstReconstructionDeviation,
						abs(reconstructed[(y * renderWidth + x) * 2] - ndcX),
					)
					worstReconstructionDeviation = max(
						worstReconstructionDeviation,
						abs(reconstructed[(y * renderWidth + x) * 2 + 1] - ndcY),
					)
				}
			}
			assertTrue(
				worstReconstructionDeviation < 0.002f,
				"every sentinel pixel must reconstruct its jitter-stripped camera motion, " +
					"worst deviation $worstReconstructionDeviation",
			)

			// Frame three - the reset branch: the companion holds a real object vector, and the
			// destination holds last frame's merged field, so the reset must blank every pixel to
			// the invalid sentinel rather than copy or reconstruct anything.
			val resetFrame = fixture.allocateAndBeginCommandBuffer()
			fixture.recordColorClear(resetFrame, velocity.image(), OBJECT_VECTOR_X, OBJECT_VECTOR_Y, 0f, 0f)
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				bridge.fillVelocity(fill(resetFrame.address(), scaleReprojection, reset = true)),
				"a reset frame must record the merge with the reset flag",
			)
			fixture.endSubmitAndWait(resetFrame)
			val resetField = fixture.readRg16fImage(motionImage.image, renderWidth, renderHeight)
			var worstResetDeviation = 0.0f
			for (value in resetField) {
				worstResetDeviation = max(worstResetDeviation, abs(value - INVALID_VELOCITY))
			}
			assertTrue(
				worstResetDeviation < 0.01f,
				"a reset frame must write the invalid sentinel everywhere, worst deviation $worstResetDeviation",
			)

			// Frame four - the complete velocity-route frame: fill, then tag, then evaluate,
			// then present, on one buffer. The tag carries the engine colour and depth only -
			// the motion source is the module's image the fill just merged, which is what the
			// evaluation consumes. This is also the frame whose timing slot completes: the fill
			// opens the chain with a real motion stage, the evaluation and present close it.
			val frame = fixture.allocateAndBeginCommandBuffer()
			fixture.recordColorClear(frame, velocity.image(), INVALID_VELOCITY, INVALID_VELOCITY, 0f, 0f)
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				bridge.fillVelocity(fill(frame.address(), scaleReprojection, reset = true)),
				"the velocity-route frame must record its merge before the tag",
			)
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				bridge.tagSrResources(
					SrTagRequest(
						frame.address(),
						ImageBinding(color.view(), color.image(), VK10.VK_FORMAT_R8G8B8A8_UNORM),
						ImageBinding(depth.view(), depth.image(), VK10.VK_FORMAT_D32_SFLOAT),
					),
				),
				"the frame's resources must tag on the same buffer, after the merge",
			)
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				bridge.evaluateSuperResolution(
					SrLiveSession.evaluationRequest(frame.address(), color, depth, renderDimensions, reset = true),
				),
				"the evaluation must consume the merged motion image",
			)
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				bridge.presentOutput(
					PresentTarget(
						frame.address(),
						presentTarget.image(),
						outputDimensions,
					),
				),
				"the frame must present so its timing slot completes",
			)
			fixture.endSubmitAndWait(frame)

			// Four fill-only frames walk the four-slot timing ring back to the complete frame's
			// slot; the fourth one's open collects it. Collection needs all four stamps of a
			// slot, so a slot whose stages did not all record can never report.
			repeat(4) {
				val walker = fixture.allocateAndBeginCommandBuffer()
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.fillVelocity(fill(walker.address(), scaleReprojection, reset = false)),
				)
				fixture.endSubmitAndWait(walker)
			}

			// The whole session must be validation-clean: the companion stayed a sampled input
			// in GENERAL, the depth came back where the engine rests it, the motion image left
			// the fill in the layout the tag and evaluation declare, and nothing submitted
			// behind the caller's back.
			SrLiveSession.assertFrameValidationClean(fixture, color, depth, images)
			val companionErrors =
				fixture.validationErrorsAbout(velocity.image(), presentTarget.image())
			assertTrue(
				companionErrors.isEmpty(),
				"the fill's frames must leave the companion and present target clean: $companionErrors",
			)

			// The completed slot's collected motion stage is the measured span of the complete
			// frame's fill - real GPU work, never the pinned zero of a skipped stage.
			val timings = bridge.frameTimings()
			assertNotNull(timings, "a completed velocity-route slot must produce collected timings")
			assertTrue(
				timings!!.motionMs > 0.0f,
				"the fill's motion stage must be a real measured stage, never a pinned zero: $timings",
			)
		}
	}

	private fun evaluation(calls: RecordingNativeApi): FrameEvaluation {
		val session = DlssSession(startupConfig())
		val adapter = LifecycleAdapter(session, calls)
		adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data"))
		val context = VulkanContext.fromNativeHandles(
			1L,
			2L,
			3L,
			4L,
			0,
			0,
			0,
			0,
			{ fakeCommandBuffer() },
			{ },
		)
		return FrameEvaluation(adapter, { context })
	}

	/**
	 * A [VkCommandBuffer] instance whose address() answers without any Vulkan device.
	 *
	 * See [MotionVectorRouteTest.fakeCommandBuffer] - the frame path only reads `address()`,
	 * so an instance allocated past the constructor is all this fake needs.
	 */
	private fun fakeCommandBuffer(): VkCommandBuffer {
		val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
		unsafeField.isAccessible = true
		val unsafe = unsafeField.get(null) as sun.misc.Unsafe
		return unsafe.allocateInstance(VkCommandBuffer::class.java) as VkCommandBuffer
	}

	private fun scene() = SceneResources(
		color = ImageBinding(201L, 202L, 37),
		depth = ImageBinding(301L, 302L, 126),
	)

	private fun jitter(): DlssJitterOffset = DlssJitter(RENDER_DIMENSIONS, OUTPUT_DIMENSIONS).advance()

	private fun motion(reset: Boolean = false) =
		DlssFrameMotion(Matrix4f(), RENDER_DIMENSIONS.width / 2f, RENDER_DIMENSIONS.height / 2f, 16.6f, reset)

	/** Records every per-frame native call so the fill gate is assertable off the render thread. */
	private class RecordingNativeApi(
		private val renderDimensions: Dimensions,
	) : NativeApiTestDouble() {
		val fills = mutableListOf<FillVelocityRequest>()
		val writeMotion = mutableListOf<MotionRequest>()
		val tags = mutableListOf<SrTagRequest>()
		val evaluations = mutableListOf<EvaluationRequest>()
		/** The per-frame recording calls in submission order, so the fill-before-tag seam is assertable. */
		val order = mutableListOf<String>()

		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		): Int = NativeApi.SUCCESS_RESULT

		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int): Dimensions =
			Dimensions(renderDimensions.width, renderDimensions.height)

		override fun configureSuperResolution(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		): Int = NativeApi.SUCCESS_RESULT

		override fun acquireImages(): EvaluationImages = EvaluationImages(
			ImageBinding(401L, 402L, 124),
			ImageBinding(501L, 502L, 37),
		)

		override fun releaseImages(): Int = NativeApi.SUCCESS_RESULT

		override fun waitDeviceIdle(): Int = NativeApi.SUCCESS_RESULT

		override fun frameTimings(): FrameTimings? = null

		override fun fillVelocity(request: FillVelocityRequest): Int {
			fills += request
			order += "fill"
			return NativeApi.SUCCESS_RESULT
		}

		override fun writeMotion(request: MotionRequest): Int {
			writeMotion += request
			order += "writeMotion"
			return NativeApi.SUCCESS_RESULT
		}

		override fun tagSrResources(request: SrTagRequest): Int {
			tags += request
			order += "tag"
			return NativeApi.SUCCESS_RESULT
		}

		override fun presentOutput(target: PresentTarget): Int {
			order += "present"
			return NativeApi.SUCCESS_RESULT
		}

		override fun evaluateSuperResolution(request: EvaluationRequest): Int {
			evaluations += request
			order += "evaluate"
			return NativeApi.SUCCESS_RESULT
		}
	}

	private companion object {
		val RENDER_DIMENSIONS = Dimensions(1280, 720)
		val OUTPUT_DIMENSIONS = Dimensions(2560, 1440)

		/** The shared invalid sentinel mc_dlss_velocity_fill.comp writes for reset and reads as "no object motion". */
		const val INVALID_VELOCITY = 10000.0f
		/** A real object vector, exact in both float32 and half float. */
		const val OBJECT_VECTOR_X = 0.25f
		const val OBJECT_VECTOR_Y = -0.5f
	}

	private fun startupConfig() = DlssStartupConfig(
		enabled = true,
		qualityMode = SRMode.PERFORMANCE,
		outputDimensions = OUTPUT_DIMENSIONS,
		sdkPath = null,
		nativeLibraryPath = null,
		dataPath = null,
		warnings = emptyList(),
	)
}
