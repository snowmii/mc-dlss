package me.snowmii.dlss.mrt

import java.nio.file.Path
import kotlin.io.path.readText
import me.snowmii.dlss.nativeSource
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.bridge.DlssEvaluationImages
import me.snowmii.dlss.bridge.DlssFrameTimings
import me.snowmii.dlss.bridge.EvaluationRequest
import me.snowmii.dlss.bridge.FillVelocityRequest
import me.snowmii.dlss.bridge.ImageBinding
import me.snowmii.dlss.bridge.MotionRequest
import me.snowmii.dlss.bridge.NativeApi
import me.snowmii.dlss.bridge.PresentTarget
import me.snowmii.dlss.bridge.SrTagRequest
import me.snowmii.dlss.bridge.VulkanContext
import me.snowmii.dlss.render.DlssFrameMotion
import me.snowmii.dlss.render.DlssJitter
import me.snowmii.dlss.render.DlssJitterOffset
import me.snowmii.dlss.render.FrameEvaluation
import me.snowmii.dlss.render.SceneResources
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.session.SRMode
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.vulkan.VkCommandBuffer

/**
 * The M-6.5 sentinel-fill gate: on the velocity-MRT route one post-scene compute dispatch
 * samples the scene depth and Minecraft's sparse RG16_FLOAT velocity companion, copies every
 * non-sentinel object vector unchanged into the module's native motion image, reconstructs
 * jitter-stripped camera motion for every sentinel pixel, and writes the invalid sentinel
 * everywhere on a reset frame. The fill lands before the tag on the same buffer, the native
 * motion image is the sole Streamline motion source (direct companion tagging is retired),
 * and the route's motion timing stage is a real measured stage, never a skipped one.
 *
 * The whole test is pure JVM like the other M-4/M-5 route tests: the frame path runs against a
 * recording [NativeApi] fake, and the native behaviour - the shader's sentinel semantics, the
 * companion's sampled-only binding, the recording order, the layout and barrier discipline,
 * the timing conversion - is pinned by source text. The five camera-only writers, their
 * mixins, and their tests are untouched; the CAMERA_ONLY route is asserted only to prove the
 * fill never rides it.
 */
class MotionVectorSentinelFillTest {
	@Test
	fun `the velocity route fills the native motion image from the sampled companion before tagging`() {
		val calls = RecordingNative(RENDER_DIMENSIONS)
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
		val calls = RecordingNative(RENDER_DIMENSIONS)
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
		val calls = RecordingNative(RENDER_DIMENSIONS)
		val evaluation = evaluation(calls)

		assertTrue(evaluation.evaluateFrame(scene(), jitter(), motion()))
		assertTrue(calls.fills.isEmpty(), "the camera-only route must keep the compute writer and no fill")
		assertEquals(1, calls.writeMotion.size)
	}

	@Test
	fun `a velocity route without a companion records nothing`() {
		val calls = RecordingNative(RENDER_DIMENSIONS)
		val evaluation = evaluation(calls)

		assertFalse(evaluation.evaluateFrame(scene(), jitter(), motion(), route = MotionVectorRoute.VELOCITY_MRT))
		assertTrue(calls.fills.isEmpty())
		assertTrue(calls.tags.isEmpty())
		assertTrue(calls.evaluations.isEmpty())
	}

	@Test
	fun `the adapter stamps the configured render dimensions onto the fill`() {
		val calls = RecordingNative(RENDER_DIMENSIONS)
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

	@Test
	fun `the fill shader copies object vectors, reconstructs camera motion for sentinels, and writes sentinel on reset`() {
		val shader = nativeSource("mc_dlss_velocity_fill.comp")

		// The companion is a sampled input only - never storage. Depth and the companion ride
		// as combined image samplers; the module's motion image is the one storage target.
		assertTrue(shader.contains("layout(set = 0, binding = 0) uniform sampler2D depthTexture"))
		assertTrue(
			shader.contains("layout(set = 0, binding = 1) uniform sampler2D velocityTexture"),
			"the companion must be sampled, never bound as storage",
		)
		assertTrue(
			shader.contains("layout(set = 0, binding = 2, rg16f) uniform writeonly image2D motionImage"),
			"the complete merged field must land in the native motion image, written and never read back",
		)
		assertFalse(
			shader.contains("image2D velocity"),
			"the companion must never be bound as a storage image",
		)

		// The sentinel gate: a pixel that already carries object motion is copied into the
		// motion image unchanged, and the dispatch is rounded up to whole workgroups so the
		// surplus invocations must be culled.
		assertTrue(shader.contains("INVALID_VELOCITY"), "the shader must name the shared invalid sentinel")
		assertTrue(shader.contains("texelFetch(velocityTexture, pixel, 0)"), "the fill must read the companion's current payload")
		assertTrue(
			shader.contains("current.x != INVALID_VELOCITY"),
			"only the sentinel payload is reconstructed",
		)
		assertTrue(
			shader.contains("imageStore(motionImage, pixel, vec4(current, 0.0, 0.0))"),
			"a non-sentinel vector must be copied unchanged, not recomputed",
		)

		// The reset guard: a reset frame writes the invalid sentinel everywhere - the motion
		// image holds last frame's merged field, so leaving stale pixels would read as motion
		// from a frame that has no predecessor.
		assertTrue(shader.contains("constants.reset"), "the reset flag must reach the shader")
		assertTrue(
			shader.contains("imageStore(motionImage, pixel, vec4(INVALID_VELOCITY, INVALID_VELOCITY, 0.0, 0.0))"),
			"a reset frame must write the invalid sentinel everywhere",
		)

		// The camera reconstruction is the camera-only pass's: jitter-stripped reprojection
		// through the pixel centre, perspective divide, NDC difference.
		assertTrue(shader.contains("vec2 ndc = ((vec2(pixel) + 0.5) / vec2(constants.renderSize)) * 2.0 - 1.0"))
		assertTrue(shader.contains("previous.xy / previous.w - ndc"))
	}

	@Test
	fun `the native fill records one dispatch with an explicit color-write to sampled-read barrier`() {
		val api = nativeSource("mc_dlss_api.cpp")
		val fill = api.substringAfter("mc_dlss_fill_velocity(").substringBefore("mc_dlss_evaluate(")
		// The fill is the velocity route's motion stage: it opens the timing chain and closes
		// the motion stamp exactly like the camera-only writer does, and its destination is the
		// module's own motion image, so it gates on the acquired images like the writer.
		assertTrue(fill.contains("begin_frame_timing"), "the fill must open the frame's timing chain")
		assertTrue(fill.contains("mark_frame_timing(recordingBuffer, 1)"), "the fill must close a real motion stage")
		assertTrue(fill.contains("images_match_configuration()"), "the fill must gate on the module's acquired images")

		val motion = nativeSource("internal/motion.cpp")
		val fillRecord = motion.substringAfter("record_velocity_fill(").substringBefore("record_motion(")
		assertTrue(fillRecord.contains("vkCmdDispatch"), "the fill must record exactly its own compute dispatch")
		assertFalse(fillRecord.contains("vkQueueSubmit"), "the fill must not submit")
		assertFalse(fillRecord.contains("vkDeviceWaitIdle"), "the fill must not idle the device")
		assertFalse(fillRecord.contains("vkQueueWaitIdle"), "the fill must not wait on a queue")

		// The companion stays in GENERAL, so no layout transition can order the dispatch
		// behind the scene's color-attachment writes: the fill owns an explicit barrier from
		// ALL_COMMANDS to the compute reads, and one after the dispatch making its writes to
		// the motion image visible to the tag and evaluation.
		assertTrue(
			fillRecord.contains("srcAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT"),
			"the fill must synchronize prior color-attachment writes into its sampled reads",
		)
		assertTrue(
			fillRecord.contains("VK_ACCESS_SHADER_READ_BIT"),
			"the pre-dispatch barrier must order the compute sampled reads",
		)
		assertTrue(fillRecord.contains("VK_IMAGE_LAYOUT_GENERAL"), "the companion must stay in GENERAL")
		assertTrue(fillRecord.contains("SHADER_WRITE"), "the fill must own the visibility of its writes")
		assertTrue(fillRecord.contains("depthEntryLayout"), "the fill must restore the depth image's entry layout")

		// The companion is a sampled binding, never a storage binding, in the fill pass: the
		// set's middle descriptor samples the companion and the storage descriptor is the
		// module's motion image.
		val bindVelocity = motion
			.substringAfter("VkDescriptorSet bind_velocity_fill_descriptors(")
			.substringBefore("// Destroys one pass")
		assertTrue(
			bindVelocity.contains("writes[1].dstBinding = 1") &&
				bindVelocity.contains("writes[1].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER"),
			"the companion must bind as a sampled image",
		)
		assertTrue(
			bindVelocity.contains("writes[2].dstBinding = 2") &&
				bindVelocity.contains("writes[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE"),
			"the motion image must bind as the storage destination",
		)
	}

	@Test
	fun `the tag no longer receives a companion and the skipped-motion accounting is removed`() {
		// Streamline tagging receives no direct companion field: the tag only tags, and the
		// native motion image is the motion source on every route.
		val tag = nativeSource("internal/sl_dlss.cpp")
			.substringAfter("int32_t tag_sr_resources(")
			.substringBefore("int32_t record_sr_evaluation(")
		assertFalse(tag.contains("begin_frame_timing"), "the tag must not open the timing chain the fill already opened")
		assertFalse(tag.contains("mark_skipped_motion_timing"), "the tag must not record a skipped motion stage")
		assertFalse(tag.contains("velocityResource"), "the tag must not describe a companion resource")
		assertFalse(tag.contains("hasVelocity"), "the tag must not branch on a companion field")

		val header = nativeSource("internal/timing.h")
		assertFalse(header.contains("motionSkipped"), "the slot must no longer carry a skipped-stage record")
		assertFalse(header.contains("mark_skipped_motion_timing"), "the explicit skip marker must be gone")

		val timing = nativeSource("internal/timing.cpp")
		assertFalse(timing.contains("motionSkipped"), "collection must no longer branch on a skip record")
		val collect = timing.substringAfter("void collect_timing(").substringBefore("void write_timing_stamp(")
		assertTrue(
			collect.contains("static_cast<float>(stamps[1] - stamps[0])"),
			"the motion stage must report its real stamp span",
		)
		assertFalse(collect.contains("? 0.0f"), "no stage may branch to a pinned zero any more")
	}

	@Test
	fun `the fill ABI carries depth, velocity, reprojection, reset, and dimensions`() {
		val header = nativeSource("mc_dlss.h")
		val fillStruct = header.substringAfter("typedef struct McDlssFillVelocityInfo {")
			.substringBefore("} McDlssFillVelocityInfo;")
		assertTrue(fillStruct.contains("McDlssImage velocity;"), "the ABI must carry the engine's velocity companion")
		assertTrue(fillStruct.contains("int32_t reset;"), "the ABI must carry the reset flag")
		assertTrue(fillStruct.contains("const float* reprojection;"))
		assertTrue(fillStruct.contains("uint32_t render_width;"))
		assertTrue(fillStruct.contains("uint32_t render_height;"))
		assertTrue(
			header.contains("MC_DLSS_API int32_t MC_DLSS_CALL mc_dlss_fill_velocity("),
			"the ABI must export the fill entry",
		)

		val java = Path.of("")
			.toAbsolutePath()
			.resolve("src/main/java/me/snowmii/dlss/bridge/Native.java")
			.readText()
		assertTrue(java.contains("FILL_LAYOUT"), "the FFM binding must mirror the fill struct")
		assertTrue(java.contains("FILL_VELOCITY_VIEW"), "the FFM fill layout must carry the velocity view field")
		assertTrue(java.contains("FILL_VELOCITY_IMAGE"), "the FFM fill layout must carry the velocity image field")
		assertTrue(java.contains("FILL_VELOCITY_FORMAT"), "the FFM fill layout must carry the velocity format field")
		assertTrue(java.contains("FILL_RESET"), "the FFM fill layout must carry the reset field")
		assertTrue(java.contains("mc_dlss_fill_velocity"), "the FFM binding must bind the native symbol")
	}

	private fun evaluation(calls: RecordingNative): FrameEvaluation {
		val session = DlssSession(startupConfig())
		val adapter = LifecycleAdapter(session, calls)
		adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data"))
		val context = VulkanContext.fromNativeHandles(
			1L,
			2L,
			3L,
			4L,
			commandBufferSource = { fakeCommandBuffer() },
			commandBufferSink = {},
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
	private class RecordingNative(
		private val RENDER_DIMENSIONS: DlssDimensions,
	) : NativeApi {
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

		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int): DlssDimensions =
			DlssDimensions(RENDER_DIMENSIONS.width, RENDER_DIMENSIONS.height)

		override fun configure(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		): Int = NativeApi.SUCCESS_RESULT

		override fun acquireImages(): DlssEvaluationImages = DlssEvaluationImages(
			motion = ImageBinding(401L, 402L, 124),
			output = ImageBinding(501L, 502L, 37),
		)

		override fun releaseImages(): Int = NativeApi.SUCCESS_RESULT

		override fun waitDeviceIdle(): Int = NativeApi.SUCCESS_RESULT

		override fun frameTimings(): DlssFrameTimings? = null

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

		override fun evaluate(request: EvaluationRequest): Int {
			evaluations += request
			order += "evaluate"
			return NativeApi.SUCCESS_RESULT
		}
	}

	private companion object {
		val RENDER_DIMENSIONS = DlssDimensions(1280, 720)
		val OUTPUT_DIMENSIONS = DlssDimensions(2560, 1440)
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
