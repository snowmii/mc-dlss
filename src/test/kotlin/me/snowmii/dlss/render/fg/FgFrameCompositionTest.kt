package me.snowmii.dlss.render.fg

import me.snowmii.dlss.render.DlssCameraSample
import me.snowmii.dlss.render.DlssFrameMotion
import me.snowmii.dlss.render.DlssJitter
import me.snowmii.dlss.render.DlssJitterOffset
import me.snowmii.dlss.render.FgFrameInputs
import me.snowmii.dlss.render.FgSurfacePolicy
import me.snowmii.dlss.render.FrameEvaluation
import me.snowmii.dlss.render.SceneResources
import me.snowmii.dlss.DlssSession
import me.snowmii.dlss.DlssStartupConfig
import me.snowmii.dlss.streamline.LifecycleAdapter
import me.snowmii.dlss.SRMode
import me.snowmii.streamline.CameraConstants
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.EvaluationImages
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.streamline.FgTagRequest
import me.snowmii.streamline.FrameTimings
import me.snowmii.dlss.api.ImageBinding
import me.snowmii.streamline.MotionRequest
import me.snowmii.streamline.PresentTarget
import me.snowmii.streamline.SrTagRequest
import me.snowmii.streamline.StreamlineSession
import me.snowmii.streamline.StreamlineSessionTestDouble
import me.snowmii.dlss.vk.VulkanContext
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.vulkan.VkCommandBuffer
import sun.misc.Unsafe
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class FgFrameCompositionTest {

	@Test
	fun `an active FG frame records configure FG tag SR tag evaluation output copy FG re-tag and one handoff`() {
		val calls = RecordingNativeApi()
		val policy = FgSurfacePolicy()
		val harness = harness(calls, policy, fgInputs())

		policy.setFrameGenerationActive(true)

		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION),
			"an active FG frame with both output-sized targets must compose and hand off",
		)
		assertEquals(
			listOf("writeMotion", "configureFg", "fgTag", "srTag", "evaluate", "present", "fgTag", "handoff"),
			calls.order,
			"the FG tag must record before the SR tag, the re-tag after the output copy, and " +
				"the handoff exactly once as the frame's terminal record",
		)
		assertEquals(1, calls.handoffs, "one active FG frame must hand off exactly once")
		assertEquals(1, harness.buffers, "one frame must record on exactly one command buffer")
		assertEquals(1, harness.submits, "the single buffer must be submitted")
		assertEquals(
			1,
			calls.fgTags.map { it.commandBuffer }.distinct().size,
			"both FG tag records must land on the same command buffer as the rest of the frame",
		)
		assertEquals(
			FgTagRequest(
				calls.fgTags.first().commandBuffer,
				scene().depth,
				fgInputs().hudless,
				fgInputs().ui,
			),
			calls.fgTags.first(),
			"the pre-SR FG tag must name the frame's depth and the resolved HUD-less and UI targets",
		)
		assertEquals(
			calls.fgTags.first(),
			calls.fgTags.last(),
			"the post-evaluation FG re-tag must name the same resources as the pre-SR tag",
		)
		assertEquals(
			FgSurfacePolicy.DEFAULT_DECLARED_BACK_BUFFERS,
			calls.fgConfigures.single(),
			"the frame's FG options must record with the back-buffer count the swapchain policy declares",
		)
	}

	@Test
	fun `an inactive FG frame records the SR frame with no FG calls`() {
		val calls = RecordingNativeApi()
		val harness = harness(calls, FgSurfacePolicy(), fgInputs())

		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION),
			"an FG-off frame must still record the SR frame",
		)
		assertEquals(
			listOf("writeMotion", "srTag", "evaluate", "present"),
			calls.order,
			"an FG-off frame must make no FG calls at all: no options, no tags, no handoff",
		)
		assertEquals(0, calls.handoffs)
		assertEquals(0, calls.fgTags.size)
		assertEquals(0, calls.fgConfigures.size)
	}

	@Test
	fun `an active FG frame without FG inputs records the SR frame with no FG calls`() {
		val calls = RecordingNativeApi()
		val policy = FgSurfacePolicy()
		val harness = harness(calls, policy, null)

		policy.setFrameGenerationActive(true)

		assertTrue(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION),
			"an active FG frame whose targets are not resolved yet must still record the SR frame",
		)
		assertEquals(
			listOf("writeMotion", "srTag", "evaluate", "present"),
			calls.order,
			"a frame without resolved FG inputs must make no FG calls: a tag naming a target " +
				"that does not exist at the output size is worse than one frame without FG",
		)
		assertEquals(0, calls.handoffs)
	}

	@Test
	fun `a refused FG tag abandons the frame before the SR tag and never hands off`() {
		val calls = RecordingNativeApi(failFgTag = true)
		val policy = FgSurfacePolicy()
		val harness = harness(calls, policy, fgInputs())

		policy.setFrameGenerationActive(true)

		assertFalse(
			harness.evaluation.evaluateFrame(scene(), jitter(), motion(), DESTINATION),
			"a refused FG tag must report the frame as not composed",
		)
		assertEquals(listOf("writeMotion", "configureFg", "fgTag"), calls.order, "a refused opening FG tag must abandon the frame before the SR tag")
		assertEquals(0, calls.handoffs, "a frame whose FG tag was refused must never hand off")
		assertEquals(1, harness.submits, "a failed recording still hands its buffer back")
	}

	@Test
	fun `the prepared camera is threaded through the evaluation and extracted into the ABI basis`() {
		val calls = RecordingNativeApi()
		val harness = harness(calls, FgSurfacePolicy(), null)

		val yaw = Math.toRadians(37.0).toFloat()
		val rotation = Matrix4f().rotationY(yaw)
		val projection = Matrix4f().perspective(1.2f, 16f / 9f, 0.05f, 1000f)
		val sample = DlssCameraSample(
			projection = projection,
			viewRotation = rotation,
			cameraX = 12.0,
			cameraY = 64.0,
			cameraZ = -48.0,
		)

		assertTrue(
			harness.evaluation.evaluateFrame(
				scene(),
				jitter(),
				motion(),
				DESTINATION,
				camera = sample,
			),
			"the frame's camera must travel with the evaluation exactly as the production wiring sends it",
		)

		val constants = calls.evaluateRequests.single().camera
		requireNotNull(constants)
		assertTrue(
			constants.right.contentEquals(floatArrayOf(rotation.m00(), rotation.m10(), rotation.m20())),
			"cameraRight must be the view rotation's column 0 (view-space +X in world)",
		)
		assertTrue(
			constants.up.contentEquals(floatArrayOf(rotation.m01(), rotation.m11(), rotation.m21())),
			"cameraUp must be the view rotation's column 1 (view-space +Y in world)",
		)
		assertTrue(
			constants.fwd.contentEquals(floatArrayOf(-rotation.m02(), -rotation.m12(), -rotation.m22())),
			"cameraFwd must be the negated view rotation's column 2 (view-space +Z is behind)",
		)
		assertTrue(
			contentEqualsTolerant(
				constants.right,
				floatArrayOf(cos(yaw), 0f, sin(yaw)),
			),
			"a yawed camera's right must be its world-space right, got " +
				constants.right.contentToString(),
		)
		assertTrue(
			contentEqualsTolerant(
				constants.fwd,
				floatArrayOf(sin(yaw), 0f, -cos(yaw)),
			),
			"a yawed camera's fwd must be its world-space view direction, got " +
				constants.fwd.contentToString(),
		)
		assertTrue(
			constants.viewToClip.contentEquals(CameraConstants.rowMajorOf(projection)),
			"viewToClip must be the sample's unjittered projection in row-major ABI layout",
		)
		assertTrue(
			constants.clipToView.contentEquals(CameraConstants.rowMajorOf(Matrix4f(projection).invert())),
			"clipToView must be the inverse projection in row-major ABI layout",
		)
		assertTrue(
			constants.pos.contentEquals(floatArrayOf(12f, 64f, -48f)),
			"cameraPos must be the sample's world position",
		)
	}

	@Test
	fun `the evaluation records every non-optional Streamline constant`() {
		val calls = RecordingNativeApi()
		val harness = harness(calls, FgSurfacePolicy(), null)

		val projection = Matrix4f().setPerspective(
			Math.toRadians(70.0).toFloat(),
			16f / 9f,
			1000f,
			0.05f,
			true,
		)
		val sample = DlssCameraSample(
			projection = projection,
			viewRotation = Matrix4f().rotationY(0.4f),
			cameraX = 1.0,
			cameraY = 2.0,
			cameraZ = 3.0,
		)
		val step = Matrix4f().translation(0.03f, -0.02f, 0.01f).rotateY(0.05f)
		val motion = DlssFrameMotion(
			Matrix4f(),
			RENDER_DIMENSIONS.width / 2f,
			RENDER_DIMENSIONS.height / 2f,
			16.6f,
			false,
			clipToPrevClip = step,
		)

		assertTrue(
			harness.evaluation.evaluateFrame(
				scene(),
				jitter(),
				motion,
				DESTINATION,
				camera = sample,
			),
		)

		val constants = calls.evaluateRequests.single().camera
		requireNotNull(constants)
		// sl_consts.h default-constructs every unwritten field to INVALID_FLOAT (3.4e38), so a
		// field the mod never sets reaches the plugin as FLT_MAX rather than as a default. The
		// DLSS-G plugin reads clipToPrevClip and the frustum scalars directly; FLT_MAX there is
		// what turned the generated frames into an upside-down world ghost while the rendered
		// frames stayed correct.
		assertTrue(
			constants.clipToPrevClip.contentEquals(CameraConstants.rowMajorOf(step)),
			"clipToPrevClip must be the frame's jitter-free camera step",
		)
		assertTrue(
			constants.prevClipToClip.contentEquals(CameraConstants.rowMajorOf(Matrix4f(step).invert())),
			"prevClipToClip must be its inverse - sl_consts.h defines it as exactly that",
		)
		assertEquals(0.05f, constants.near, 1e-4f, "cameraNear")
		assertEquals(1000f, constants.far, 0.5f, "cameraFar")
		assertEquals(Math.toRadians(70.0).toFloat(), constants.fovRadians, 1e-3f, "cameraFOV")
		assertEquals(16f / 9f, constants.aspectRatio, 1e-3f, "cameraAspectRatio")
		for (value in listOf(constants.near, constants.far, constants.fovRadians, constants.aspectRatio)) {
			assertTrue(value < invalidFloat, "no scalar may reach the plugin as INVALID_FLOAT")
		}
	}

	private fun contentEqualsTolerant(actual: FloatArray, expected: FloatArray): Boolean {
		if (actual.size != expected.size) {
			return false
		}
		for (i in actual.indices) {
			if (abs(actual[i] - expected[i]) > 1e-5f) {
				return false
			}
		}
		return true
	}

	private fun harness(
		calls: RecordingNativeApi,
		policy: FgSurfacePolicy,
		inputs: FgFrameInputs?,
	): Harness {
		val session = DlssSession(
			DlssStartupConfig(
				enabled = true,
				qualityMode = SRMode.QUALITY,
				outputDimensions = OUTPUT_DIMENSIONS,
				sdkPath = null,
				nativeLibraryPath = null,
				dataPath = null,
				warnings = emptyList(),
			),
		)
		val adapter = LifecycleAdapter(session, calls)
		adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data"))
		val counters = Counters()
		val context = VulkanContext.fromNativeHandles(
			1L,
			2L,
			3L,
			4L,
			0,
			0,
			0,
			0,
			{
				counters.buffers++
				fakeCommandBuffer()
			},
			{ counters.submits++ },
		)
		return Harness(
			evaluation = FrameEvaluation(
				adapter,
				{ context },
				frameGeneration = policy,
				fgInputs = { inputs },
			),
			counters = counters,
		)
	}

	private fun fakeCommandBuffer(): VkCommandBuffer {
		val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
		unsafeField.isAccessible = true
		val unsafe = unsafeField.get(null) as Unsafe
		return unsafe.allocateInstance(VkCommandBuffer::class.java) as VkCommandBuffer
	}

	private fun scene() = SceneResources(
		color = ImageBinding(201L, 202L, 37),
		depth = ImageBinding(301L, 302L, 126),
	)

	private fun fgInputs() = FgFrameInputs(
		hudless = ImageBinding(601L, 602L, 37),
		ui = ImageBinding(701L, 702L, 37),
	)

	private fun jitter(): DlssJitterOffset = DlssJitter(RENDER_DIMENSIONS, OUTPUT_DIMENSIONS).advance()

	/** sl_consts.h: `constexpr float INVALID_FLOAT = 3.402823466e38f`. */
	private val invalidFloat = 3.402823466e38f

	private fun motion() =
		DlssFrameMotion(Matrix4f(), RENDER_DIMENSIONS.width / 2f, RENDER_DIMENSIONS.height / 2f, 16.6f, false)

	private class Harness(
		val evaluation: FrameEvaluation,
		private val counters: Counters,
	) {
		val buffers: Int
			get() = counters.buffers
		val submits: Int
			get() = counters.submits
	}

	private class Counters {
		var buffers = 0
		var submits = 0
	}

	private class RecordingNativeApi(
		private val failFgTag: Boolean = false,
	) : StreamlineSessionTestDouble() {
		val order = mutableListOf<String>()
		val fgTags = mutableListOf<FgTagRequest>()
		val fgConfigures = mutableListOf<Int>()
		val evaluateRequests = mutableListOf<EvaluationRequest>()
		var handoffs = 0

		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		): Int = StreamlineSession.SUCCESS_RESULT

		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int): Dimensions =
			RENDER_DIMENSIONS

		override fun configureSuperResolution(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		): Int = StreamlineSession.SUCCESS_RESULT

		override fun acquireImages(): EvaluationImages = EvaluationImages(
			ImageBinding(401L, 402L, 124),
			ImageBinding(501L, 502L, 37),
		)

		override fun releaseImages(): Int = StreamlineSession.SUCCESS_RESULT

		override fun waitDeviceIdle(): Int = StreamlineSession.SUCCESS_RESULT

		override fun frameTimings(): FrameTimings? = null

		override fun configureFg(numBackBuffers: Int): Int {
			fgConfigures += numBackBuffers
			order += "configureFg"
			return StreamlineSession.SUCCESS_RESULT
		}

		override fun tagFrameGenerationResources(request: FgTagRequest): Int {
			fgTags += request
			order += "fgTag"
			return if (failFgTag) StreamlineSession.SUCCESS_RESULT + 1 else StreamlineSession.SUCCESS_RESULT
		}

		override fun recordPresentHandoff(): Int {
			handoffs++
			order += "handoff"
			return StreamlineSession.SUCCESS_RESULT
		}

		override fun tagSrResources(request: SrTagRequest): Int {
			order += "srTag"
			return StreamlineSession.SUCCESS_RESULT
		}

		override fun writeMotion(request: MotionRequest): Int {
			order += "writeMotion"
			return StreamlineSession.SUCCESS_RESULT
		}

		override fun presentOutput(target: PresentTarget): Int {
			order += "present"
			return StreamlineSession.SUCCESS_RESULT
		}

		override fun evaluateSuperResolution(request: EvaluationRequest): Int {
			order += "evaluate"
			evaluateRequests += request
			return StreamlineSession.SUCCESS_RESULT
		}
	}

	private companion object {
		val RENDER_DIMENSIONS = Dimensions(1280, 720)
		val OUTPUT_DIMENSIONS = Dimensions(2560, 1440)

		const val DESTINATION = 900L
	}
}
