package me.snowmii.dlss.fg

import java.nio.file.Path
import me.snowmii.dlss.bridge.CameraConstants
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.bridge.DlssEvaluationImages
import me.snowmii.dlss.bridge.DlssFrameTimings
import me.snowmii.dlss.bridge.EvaluationRequest
import me.snowmii.dlss.bridge.FgTagRequest
import me.snowmii.dlss.bridge.FillVelocityRequest
import me.snowmii.dlss.bridge.ImageBinding
import me.snowmii.dlss.bridge.MotionRequest
import me.snowmii.dlss.bridge.NativeApi
import me.snowmii.dlss.bridge.PresentTarget
import me.snowmii.dlss.bridge.SrTagRequest
import me.snowmii.dlss.bridge.VulkanContext
import me.snowmii.dlss.bridge.rowMajorOf
import me.snowmii.dlss.render.DlssCameraSample
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
import org.joml.Vector4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.vulkan.VkCommandBuffer

/**
 * M-11 image Y convention (AC-2): the matrices the frame records into `slSetConstants` and
 * the rasterized images share one pixel<->NDC Y convention, and a non-symmetric reprojection
 * round trip cannot silently invert Y.
 *
 * The engine rasterizes the world with Minecraft 26.2's positive-height Vulkan viewport,
 * whose framebuffer origin is top-left: image row 0 holds NDC y = -1, and a JOML perspective
 * (view-space +Y up projects to positive NDC y) puts the world's up at the bottom of every
 * image the mod hands DLSS-G - scene colour and depth, the motion field, and the output-sized
 * HUD-less/UI targets alike - while the present blit flips the composed frame back for the
 * display. The motion writers share that mapping: pixel (x, y) is ndc (2x/w - 1, 2y/h - 1),
 * no flip, so the motion field needs no change.
 *
 * Handed the raw projection, the plugin read the images as vertically flipped relative to
 * the matrices, and its reprojection of the previous frame inverted Y - the upside-down
 * semi-transparent world ghost the human probe saw on every generated frame while rendered
 * frames stayed correct. That symptom diagnosed the matrices as the disagreeing side, so
 * the frame records them expressed in the convention the rasterized images follow, and the
 * oracle rung ([FgCameraConstantsTest]) proves the payload reaches
 * `slSetConstants` unchanged; the composition rung ([FgFrameCompositionTest]) proves
 * [FrameEvaluation] hands the converted payload to the ABI.
 *
 * These tests pin the conversion's *behavior* through the production seam: each drives
 * [FrameEvaluation.evaluateFrame] with a skewed, off-axis camera projection and reads the
 * payload the frame handed the bridge - the exact floats production records into
 * `slSetConstants` - then evaluates that payload under Streamline's row-vector ABI
 * (`v' = v * M`, row-major layout, flat index row * 4 + col). Nothing here mirrors the
 * flip's index arithmetic: the oracles are the engine's own matrix math and the round-trip
 * identity, so a flip on the wrong side of either matrix (row instead of column, or column
 * instead of row) fails these proofs loudly.
 */
class FgImageConventionTest {

	@Test
	fun `the recorded projection negates exactly the clip-space Y of the rendered projection`() {
		val projection = skewedProjection()
		val recorded = capture(projection)
		val raw = rowMajorOf(projection)

		// Anchor: the raw payload is the projection the world rendered with - evaluating it in
		// the Streamline row-vector convention answers JOML's own column-vector transform.
		for (point in VIEW_POINTS) {
			assertVectorsEqual(
				projection.transform(Vector4f(point)),
				rowVectorTransform(raw, Vector4f(point)),
				1e-6f,
				"raw payload of $point",
			)
		}

		// The flip negates exactly the clip-space Y output of every point and nothing else.
		// Negating the wrong side - the row the view-space Y *input* travels through - moves
		// x and z too (the skewed projection's row 1 and column 1 differ) and fails here.
		for (point in VIEW_POINTS) {
			val unflipped = rowVectorTransform(raw, Vector4f(point))
			val flipped = rowVectorTransform(recorded.viewToClip, Vector4f(point))
			assertEquals(unflipped.x, flipped.x, 1e-6f, "clip x of $point must not move")
			assertEquals(-unflipped.y, flipped.y, 1e-6f, "clip y of $point must negate exactly")
			assertEquals(unflipped.z, flipped.z, 1e-6f, "clip z of $point must not move")
			assertEquals(unflipped.w, flipped.w, 1e-6f, "clip w of $point must not move")
		}

		// The flip points the right way: view up lands at negative clip y - the bottom of
		// DLSS-G's reading convention (image row 0 = NDC +1), exactly where the rasterizer
		// (row 0 = NDC -1, top-left origin) draws the world's up. Never the other way round.
		val up = rowVectorTransform(recorded.viewToClip, Vector4f(0f, 1f, -5f, 1f))
		assertTrue(up.y / up.w < 0f, "view up must land in negative clip y after the flip")
		val down = rowVectorTransform(recorded.viewToClip, Vector4f(0f, -1f, -5f, 1f))
		assertTrue(down.y / down.w > 0f, "view down must land in positive clip y after the flip")
	}

	@Test
	fun `a non-symmetric reprojection round-trips through the recorded pair`() {
		val recorded = capture(skewedProjection())

		// The plugin reprojects the previous frame as clip = view * viewToClip and
		// view = clip * clipToView. A flip on the wrong side of either matrix - negating the
		// inverse's column instead of its row, or the projection's row instead of its column -
		// breaks this identity for every non-symmetric projection, so an inverted Y cannot
		// hide here.
		for (point in VIEW_POINTS) {
			val clip = rowVectorTransform(recorded.viewToClip, Vector4f(point))
			val roundTripped = rowVectorTransform(recorded.clipToView, clip)
			assertEquals(point.x, roundTripped.x, 1e-4f, "x of $point")
			assertEquals(point.y, roundTripped.y, 1e-4f, "y of $point")
			assertEquals(point.z, roundTripped.z, 1e-4f, "z of $point")
			assertEquals(point.w, roundTripped.w, 1e-4f, "w of $point")
		}
	}

	@Test
	fun `each image row reads back the direction the rasterizer drew there`() {
		val projection = skewedProjection()
		val recorded = capture(projection)
		val rasterInverse = rowMajorOf(Matrix4f(projection).invert())

		// For a row the rasterizer drew at NDC y (row 0 = NDC -1, the motion writers' mapping),
		// the recorded inverse fed the DLSS-G reading of that same row (row 0 = NDC +1, the
		// negated NDC) must recover the same view-space direction as the raw inverse: matrices
		// and images then share one Y convention, which is what keeps the plugin's
		// reprojection from inverting the world. A flip on the inverse's wrong side breaks
		// this for the skewed projection - its inverse's row 1 and column 1 differ.
		val height = 720
		for (row in intArrayOf(0, 180, 360, 719)) {
			val ndcRaster = 2f * (row + 0.5f) / height - 1f
			val viaRaster = rowVectorTransform(rasterInverse, Vector4f(0.3f, ndcRaster, 0.5f, 1f))
			val viaRecorded = rowVectorTransform(recorded.clipToView, Vector4f(0.3f, -ndcRaster, 0.5f, 1f))
			assertEquals(viaRaster.x / viaRaster.w, viaRecorded.x / viaRecorded.w, 1e-4f, "direction x at row $row")
			assertEquals(viaRaster.y / viaRaster.w, viaRecorded.y / viaRecorded.w, 1e-4f, "direction y at row $row")
			assertEquals(viaRaster.z / viaRaster.w, viaRecorded.z / viaRecorded.w, 1e-4f, "direction z at row $row")
		}
	}

	/**
	 * Drives the production seam: one SR evaluation through [FrameEvaluation] whose camera
	 * carries [projection], and reads back the ABI payload the frame handed the bridge - the
	 * exact floats production records into `slSetConstants` (the composition rung proves that
	 * wiring; these rungs read the payload it produced).
	 */
	private fun capture(projection: Matrix4f): CameraConstants {
		val calls = RecordingNative()
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
		val context = VulkanContext.fromNativeHandles(
			1L,
			2L,
			3L,
			4L,
			commandBufferSource = { fakeCommandBuffer() },
			commandBufferSink = {},
		)
		val evaluation = FrameEvaluation(adapter, { context })
		assertTrue(
			evaluation.evaluateFrame(
				scene(),
				jitter(),
				motion(),
				camera = DlssCameraSample(
					projection = projection,
					viewRotation = Matrix4f(),
					cameraX = 12.0,
					cameraY = 64.0,
					cameraZ = -48.0,
				),
			),
			"the SR frame must record and evaluate",
		)
		return requireNotNull(calls.evaluateRequests.single().camera)
	}

	/**
	 * Streamline's ABI multiplication: the payload is row-major and the vector convention is
	 * row-vector, so out_j = sum_k v_k * M[k][j] with M[k][j] = payload[k * 4 + j]. The
	 * arithmetic lands on the same numbers as JOML's column-vector transform of the matrix the
	 * payload came from - the two conventions differ twice and cancel - which the first test's
	 * raw-payload anchor checks against [Matrix4f.transform].
	 */
	private fun rowVectorTransform(matrix: FloatArray, vector: Vector4f): Vector4f = Vector4f(
		vector.x * matrix[0] + vector.y * matrix[4] + vector.z * matrix[8] + vector.w * matrix[12],
		vector.x * matrix[1] + vector.y * matrix[5] + vector.z * matrix[9] + vector.w * matrix[13],
		vector.x * matrix[2] + vector.y * matrix[6] + vector.z * matrix[10] + vector.w * matrix[14],
		vector.x * matrix[3] + vector.y * matrix[7] + vector.z * matrix[11] + vector.w * matrix[15],
	)

	private fun assertVectorsEqual(expected: Vector4f, actual: Vector4f, tolerance: Float, context: String) {
		assertEquals(expected.x, actual.x, tolerance, "$context x")
		assertEquals(expected.y, actual.y, tolerance, "$context y")
		assertEquals(expected.z, actual.z, tolerance, "$context z")
		assertEquals(expected.w, actual.w, tolerance, "$context w")
	}

	/** A skewed, off-axis projection whose row 1 and column 1 differ, so a wrong-side flip fails. */
	private fun skewedProjection(): Matrix4f =
		Matrix4f().perspective(1.2217305f, 16f / 9f, 0.05f, 1000f, true)
			.mul(Matrix4f().rotationY(0.31f).rotationX(0.17f))
			.translate(0.21f, -0.13f, 0.07f)

	private fun scene() = SceneResources(
		color = ImageBinding(201L, 202L, 37),
		depth = ImageBinding(301L, 302L, 126),
	)

	private fun jitter(): DlssJitterOffset = DlssJitter(RENDER_DIMENSIONS, OUTPUT_DIMENSIONS).advance()

	private fun motion() =
		DlssFrameMotion(Matrix4f(), RENDER_DIMENSIONS.width / 2f, RENDER_DIMENSIONS.height / 2f, 16.6f, false)

	/** A [VkCommandBuffer] instance whose address() answers without any Vulkan device. */
	private fun fakeCommandBuffer(): VkCommandBuffer {
		val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
		unsafeField.isAccessible = true
		val unsafe = unsafeField.get(null) as sun.misc.Unsafe
		return unsafe.allocateInstance(VkCommandBuffer::class.java) as VkCommandBuffer
	}

	/** Records the frame's evaluation request; everything else answers success like the composition rung's fake. */
	private class RecordingNative : NativeApi {
		val evaluateRequests = mutableListOf<EvaluationRequest>()

		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		): Int = NativeApi.SUCCESS_RESULT

		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int): DlssDimensions =
			RENDER_DIMENSIONS

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

		override fun configureFg(numBackBuffers: Int): Int = NativeApi.SUCCESS_RESULT

		override fun tagFgResources(request: FgTagRequest): Int = NativeApi.SUCCESS_RESULT

		override fun presentHandoff(): Int = NativeApi.SUCCESS_RESULT

		override fun tagSrResources(request: SrTagRequest): Int = NativeApi.SUCCESS_RESULT

		override fun writeMotion(request: MotionRequest): Int = NativeApi.SUCCESS_RESULT

		override fun fillVelocity(request: FillVelocityRequest): Int = NativeApi.SUCCESS_RESULT

		override fun presentOutput(target: PresentTarget): Int = NativeApi.SUCCESS_RESULT

		override fun evaluate(request: EvaluationRequest): Int {
			evaluateRequests += request
			return NativeApi.SUCCESS_RESULT
		}
	}

	private companion object {
		val RENDER_DIMENSIONS = DlssDimensions(1280, 720)
		val OUTPUT_DIMENSIONS = DlssDimensions(2560, 1440)

		/** Points with non-zero view-space Y and non-zero clip-space Y, so either wrong-side flip breaks loudly. */
		val VIEW_POINTS = arrayOf(
			Vector4f(0.3f, 0.7f, -5f, 1f),
			Vector4f(-0.5f, -0.9f, -12f, 1f),
			Vector4f(0f, 0f, -8f, 1f),
			Vector4f(1.3f, 0.4f, -3.2f, 1f),
		)
	}
}
