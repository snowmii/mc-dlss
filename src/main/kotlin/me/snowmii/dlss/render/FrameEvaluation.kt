package me.snowmii.dlss.render
import me.snowmii.streamline.CameraConstants
import me.snowmii.streamline.EvaluationImages
import me.snowmii.streamline.FrameTimings
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.streamline.FgTagRequest
import me.snowmii.streamline.FgState
import me.snowmii.streamline.FillVelocityRequest
import me.snowmii.streamline.ImageBinding
import me.snowmii.streamline.MotionRequest
import me.snowmii.streamline.StreamlineSession
import me.snowmii.streamline.PresentTarget

import me.snowmii.streamline.SrTagRequest
import me.snowmii.streamline.Vec2
import me.snowmii.streamline.VulkanContext
import me.snowmii.dlss.fg.FgSurfacePolicy
import me.snowmii.dlss.mrt.MotionVectorRoute
import me.snowmii.dlss.readout.SessionReadout
import me.snowmii.dlss.session.LifecycleAdapter
import org.joml.Matrix4f
import org.joml.Vector4f
import kotlin.math.abs
import org.lwjgl.vulkan.VkCommandBuffer

/**
 * Engine colour and depth from the world phase. Motion and output are the bridge's own and
 * never appear here.
 */
data class SceneResources(
	val color: ImageBinding,
	val depth: ImageBinding,
)

/**
 * One frame's DLSS-G inputs. Depth and motion come from the scene and the bridge, so only
 * these two cross. Null resolution records SR-only.
 */
data class FgFrameInputs(
	val hudless: ImageBinding,
	val ui: ImageBinding,
)

/**
 * Extracts the frustum scalars Streamline's `sl::Constants` requires from the engine's
 * view-to-clip projection: near, far, vertical field of view in radians, and aspect ratio.
 *
 * The plane distances come out of the inverse rather than off JOML's `perspectiveNear`/
 * `perspectiveFar`, which read the matrix as if it were built the conventional way round.
 * Minecraft 26.2 renders reversed-Z by passing its near and far to `setPerspective` swapped,
 * and those accessors then answer the far distance twice (0.05 and 0.05 for a 0.05/1000
 * frustum). Un-projecting the two ends of Vulkan's [0, 1] NDC depth range instead asks the
 * matrix what it actually does, which is the same question under either convention; the
 * smaller view-space distance is the near plane.
 *
 * Aspect comes off the matrix rather than the window because the projection is what the frame
 * was actually rasterized with, bob and skew included: for any perspective matrix
 * m00 = 1/(aspect * tan(fov/2)) and m11 = 1/tan(fov/2), so aspect is m11/m00.
 */
internal fun dlssFrustum(projection: Matrix4f): FloatArray {
	val inverse = Matrix4f(projection).invert()
	val atDepthZero = inverse.transform(Vector4f(0f, 0f, 0f, 1f))
	val atDepthOne = inverse.transform(Vector4f(0f, 0f, 1f, 1f))
	// View space looks down -Z, so the distance to a plane is the negated view-space z.
	val firstPlane = abs(atDepthZero.z / atDepthZero.w)
	val secondPlane = abs(atDepthOne.z / atDepthOne.w)
	val aspect = if (projection.m00() != 0f) projection.m11() / projection.m00() else 0f
	return floatArrayOf(
		minOf(firstPlane, secondPlane),
		maxOf(firstPlane, secondPlane),
		projection.perspectiveFov(),
		aspect,
	)
}

/**
 * Records one frame's DLSS work onto Minecraft's graphics submission. The only place in the
 * mod that touches a command buffer.
 *
 * Ordering is the contract. All calls go on **one** buffer: motion, then resource tags, then
 * evaluation. Evaluation reads the image the motion pass writes (the pass ends with the
 * barrier) and consumes the Streamline frame token the tag obtained. An FG-active frame wraps
 * that chain on the same buffer and token — FG options and FG tag before the SR tag, SR
 * evaluation, FG re-tag, present handoff. Inactive: SR only. Buffer comes from Minecraft's
 * shared encoder and goes straight back; nothing here submits, fences, or idles.
 *
 * A failed stage still hands the buffer back. Native restores layouts whether evaluation
 * succeeded or not; dropping the buffer would leave Minecraft an image in a layout its next
 * pass does not expect.
 */
class FrameEvaluation(
	private val adapter: LifecycleAdapter,
	private val context: () -> VulkanContext?,
	private val readout: SessionReadout? = null,
	/**
	 * Active policy wraps SR with DLSS-G; inactive keeps the recording SR-only.
	 */
	private val frameGeneration: FgSurfacePolicy = FgSurfacePolicy(),
	/**
	 * Null records SR-only. Production resolves main + UI at output size; a missing or
	 * stale-sized UI target stays SR-only — tagging an image the frame is about to destroy
	 * is worse than one frame without FG.
	 */
	private val fgInputs: () -> FgFrameInputs? = { null },
) : AutoCloseable {
	private var nativeEvaluationImages: EvaluationImages? = null
	private var firstEvaluationReported = false

	fun presentStart(): Boolean = adapter.presentStart()
	fun presentEnd(): Boolean = adapter.presentEnd()

	fun reflexInputSample(): Boolean = adapter.reflexInputSample()
	fun reflexMarker(type: StreamlineSession.ReflexMarkerType): Boolean = adapter.reflexMarker(type)

	/** The native-owned images this evaluation writes into, or null before the first frame. */
	val evaluationImages: EvaluationImages?
		get() = nativeEvaluationImages

	/**
	 * Records motion then evaluation. True only when both recorded; false latches the failure.
	 *
	 * [camera] threads into `slSetConstants` for DLSS-G. Null records a zero-filled camera;
	 * production always carries one (unobserved camera publishes no motion, so evaluation is
	 * skipped first).
	 */
	fun evaluateFrame(
		scene: SceneResources,
		jitter: DlssJitterOffset,
		motion: DlssFrameMotion,
		destinationImage: Long = NO_DESTINATION,
		route: MotionVectorRoute = MotionVectorRoute.CAMERA_ONLY,
		velocity: ImageBinding? = null,
		camera: DlssCameraSample? = null,
	): Boolean {
		val vulkan = context() ?: return false
		val held = nativeEvaluationImages ?: adapter.acquireImages()?.also { nativeEvaluationImages = it } ?: return false

		val buffer = vulkan.allocateRecordingCommandBuffer()
		// The FG frame composes its DLSS-G record around the SR evaluation, and only when the
		// policy is active AND the runtime resolved this frame's FG inputs. An inactive frame or
		// one without both output-sized targets records SR-only: no FG options, no FG tags, no
		// handoff. Resolved here rather than inside [record] because the record's two halves
		// straddle the output copy — see [recordFrameGenerationStart] and
		// [recordFrameGenerationEnd].
		val fg = if (frameGeneration.effective) fgInputs() else null
		val handle = buffer.address()
		// The motion stage opens the recording, ahead of the FG record: it fills the module's
		// motion copy, which the FG tag below names as the frame's motion source.
		val recorded = recordMotion(handle, scene, route, velocity, motion) &&
			recordFrameGenerationStart(handle, scene, fg) &&
			recordSuperResolution(buffer, scene, jitter, motion, camera) &&
			(destinationImage == NO_DESTINATION || copyDlssOutput(buffer, destinationImage)) &&
			recordFrameGenerationEnd(handle, scene, fg)
		// Submitted on every path: see the class comment - an abandoned buffer is what actually
		// breaks the renderer, not a failed evaluation.
		vulkan.enqueueOnEngineEncoder(buffer)
		reportFirstEvaluation(recorded, scene, held)
		return recorded
	}

	/**
	 * GPU timings of the last frame that completed every recorded stage, or null when none has.
	 *
	 * Asked for when something wants to report them rather than every frame, because the answer
	 * only changes as fast as frames complete and the call crosses the ABI to read it.
	 */
	fun sampleTimings(): FrameTimings? = adapter.frameTimings()

	/**
	 * The DLSS-G plugin's live state for the frame-rate monitor, or null when no session is
	 * ready. Read-only: the monitor reports, it never gates or latches the session.
	 */
	fun sampleFgState(): FgState? = adapter.queryFgState()

	/**
	 * Releases the native-owned images.
	 *
	 * The next eligible frame acquires them again, which is what a configuration change needs:
	 * the images are sized from the configuration and outlive nothing that changes it.
	 */
	override fun close() {
		if (nativeEvaluationImages == null) {
			return
		}

		nativeEvaluationImages = null
		adapter.releaseImages()
	}

	/**
	 * Records the copy of the upscaled output into the engine target, after the evaluation that
	 * wrote it and on the same buffer.
	 *
	 * Recorded here rather than by the world phase because the ordering is the whole point: the
	 * copy has to sit behind the evaluation in one recording, and this is the only place holding
	 * that recording.
	 */
	private fun copyDlssOutput(buffer: VkCommandBuffer, destinationImage: Long): Boolean = adapter.presentOutput(
		PresentTarget(buffer.address(), destinationImage),
	)

	private fun recordSuperResolution(
		buffer: VkCommandBuffer,
		scene: SceneResources,
		jitter: DlssJitterOffset,
		motion: DlssFrameMotion,
		camera: DlssCameraSample?,
	): Boolean {
		val handle = buffer.address()

		// Tag between motion and evaluation on the same buffer: obtains the Streamline frame
		// token the evaluation consumes. Motion is always the native motion image, so the tag
		// request is route-independent.
		val tagged = adapter.tagSrResources(
			SrTagRequest(
				handle,
				scene.color,
				scene.depth,
			),
		)
		if (!tagged) {
			return false
		}

		// The two inputs that decide whether DLSS accumulates at all, recorded per evaluated frame
		// so a session that stays aliased while standing still says which one failed.
		readout?.recordFrameJitter(jitter.index, jitter.pixelX, jitter.pixelY, motion.reset)
		val evaluated = adapter.evaluate(
			EvaluationRequest.builder()
				.commandBuffer(handle)
				.color(scene.color)
				.depth(scene.depth)
				// NGX takes the offset in render pixels, which is the unit the sequence is in.
				.jitter(Vec2(jitter.pixelX, jitter.pixelY))
				.motionScale(Vec2(motion.motionScaleX, motion.motionScaleY))
				.frameTimeMilliseconds(motion.frameTimeMillis)
				.resetHistory(motion.reset)
				.camera(camera?.let { cameraConstants(it, motion) })
				.build(),
		)
		return evaluated
	}

	/**
	 * Records the frame's motion stage, the route's choice, and reports whether it took.
	 *
	 * VELOCITY_MRT: post-scene fill merges the scene velocity companion into the native motion
	 * image (the sole Streamline motion source) and does not run the compute camera-motion
	 * writer. CAMERA_ONLY: compute writer as usual. A VELOCITY_MRT frame without a velocity
	 * companion has no motion source and is refused: evaluating with no motion vectors is
	 * worse than one frame of the low-resolution present.
	 */
	private fun recordMotion(
		handle: Long,
		scene: SceneResources,
		route: MotionVectorRoute,
		velocity: ImageBinding?,
		motion: DlssFrameMotion,
	): Boolean = when (route) {
		// The fill comes first in the frame's recording, before the tag: it opens the native
		// timing chain with a real motion stage, and the tag must find the native motion image
		// already merged so the motion it names is complete.
		MotionVectorRoute.VELOCITY_MRT -> velocity != null && adapter.fillVelocity(
			FillVelocityRequest(
				handle,
				scene.depth,
				velocity,
				FloatArray(16).also { motion.reprojection.get(it) },
				motion.reset,
				null,
			),
		)

		MotionVectorRoute.CAMERA_ONLY -> adapter.writeMotion(
			MotionRequest(
				handle,
				scene.depth,
				FloatArray(16).also { motion.reprojection.get(it) },
				null,
			),
		)
	}.also { recorded ->
		if (recorded) {
			MotionProbe.recordFrame(motion)
		}
	}

	fun motionProbeLine(): String = MotionProbe.line(adapter.queryMotionProbe())

	/**
	 * The composed frame's opening DLSS-G record: this frame's options, then its first FG tag.
	 *
	 * The options record first because the tag refuses until the stored configuration has them,
	 * and a per-frame record also heals the invalidation a replaced SR configuration leaves
	 * behind; the handoff at the end of the frame re-records the same options, which is the
	 * guide's per-frame `slDLSSGSetOptions`.
	 *
	 * The tag records BEFORE the SR tag, and this ordering is not cosmetic. It obtains the
	 * Streamline frame token the SR tag then reuses, and the native evaluation reads
	 * `fgTagFrameIndexRecorded` to decide two things: whether to record the FG viewport's
	 * (y-flipped) camera constants at all, and whether to retain the frame token past the
	 * evaluation for the handoff to consume. A frame whose first FG tag comes after the
	 * evaluation gets neither - it loses its FG constants and takes a fresh token, so its
	 * handoff sees mismatched SR and FG frame indexes, refuses, and latches the fallback that
	 * takes SR down with it. The common plugin also holds one tag per (buffer type, viewport)
	 * per frame, and the SR evaluation must read the depth and motion slots as the SR tag last
	 * wrote them, so the FG tag cannot be the last writer before it either.
	 *
	 * An SR-only frame passes straight through: no options, no tag, and the evaluation is then
	 * free to consume the token itself.
	 */
	private fun recordFrameGenerationStart(handle: Long, scene: SceneResources, fg: FgFrameInputs?): Boolean =
		fg == null ||
			(adapter.configureFg(frameGeneration.requiredSwapchainImages) && tagFg(handle, scene, fg))

	/**
	 * The composed frame's closing DLSS-G record: the post-evaluation FG re-tag and the present
	 * handoff, both after the SR output copy.
	 *
	 * The re-tag is the declaration the present path needs - the evaluation's restore has left
	 * every tagged resource in the engine-resting GENERAL layout these tags declare - and it is
	 * also where `tag_fg_resources` records the frame's orientation blits, which is why it sits
	 * after `present` rather than before it.
	 *
	 * Those blits are DLSS-G's snapshot: depth, HUD-less colour, and UI copied into
	 * module-owned images the plugin reads at present. Timing decides what interpolates.
	 * After the output copy the main target holds this frame's world only (HUD draws later),
	 * so the blit is genuinely HUD-less. `mc_dlss_present_output` returns the main target to
	 * GENERAL, the layout the blit reads.
	 *
	 * The handoff is then the frame's terminal act, consuming the retained token exactly once so
	 * the next frame's tags advance it.
	 */
	private fun recordFrameGenerationEnd(handle: Long, scene: SceneResources, fg: FgFrameInputs?): Boolean =
		fg == null || (tagFg(handle, scene, fg) && adapter.presentHandoff())

	private fun tagFg(handle: Long, scene: SceneResources, fg: FgFrameInputs): Boolean =
		adapter.tagFgResources(
			FgTagRequest(
				handle,
				scene.depth,
				fg.hudless,
				fg.ui,
			),
		)

	/**
	 * Feeds the first evaluation to the session readout exactly once.
	 *
	 * A recorded evaluation and a session that silently never reached one look identical from
	 * outside - the frame renders either way. The line names which stage the frame actually got
	 * through and the images it wrote into, which is enough to tell them apart from the log alone.
	 */
	private fun reportFirstEvaluation(
		recorded: Boolean,
		scene: SceneResources,
		held: EvaluationImages,
	) {
		if (firstEvaluationReported) {
			return
		}

		firstEvaluationReported = true
		readout?.reportFirstEvaluation(
			recorded = recorded,
			colorImage = scene.color.image,
			depthImage = scene.depth.image,
			motionImage = held.motion.image,
			outputImage = held.output.image,
		)
	}

	/**
	 * Converts one camera sample into the flat ABI constants the evaluation carries.
	 *
	 * [DlssCameraSample.projection] is already the jitter-free view-to-clip projection the
	 * world rendered with (the jitter is applied after the seam captures it), so it converts
	 * into [CameraConstants.viewToClip] through [CameraConstants.rowMajorOf] unchanged; the clip-to-view
	 * inverse follows from it, the frustum scalars from [dlssFrustum], and the clip-to-prev-clip
	 * pair from [motion] - the same camera step the motion pass reprojects with, minus the
	 * jitter conjugation SL forbids in its matrices.
	 * The orthonormal basis comes from the view rotation's rows: JOML names its elements
	 * m<column><row>, so row c of the matrix is (m0c, m1c, m2c) - the view-space axes
	 * expressed in world coordinates. Row 0 is the camera's right (view-space +X), row 1 its
	 * up (view-space +Y), and row 2 is view-space +Z - the direction *behind* the camera,
	 * hence the sign flip for the forward the plugin expects (the direction the camera looks).
	 * Reading the columns instead would hand the plugin the world axes expressed in view
	 * space - the transpose - which only coincides with the basis for the identity.
	 */
	private fun cameraConstants(camera: DlssCameraSample, motion: DlssFrameMotion): CameraConstants {
		val rotation = camera.viewRotation
		val frustum = dlssFrustum(camera.projection)
		return CameraConstants(
			CameraConstants.rowMajorOf(camera.projection),
			CameraConstants.rowMajorOf(Matrix4f(camera.projection).invert()),
			floatArrayOf(
				camera.cameraX.toFloat(),
				camera.cameraY.toFloat(),
				camera.cameraZ.toFloat(),
			),
			floatArrayOf(rotation.m00(), rotation.m10(), rotation.m20()),
			floatArrayOf(rotation.m01(), rotation.m11(), rotation.m21()),
			floatArrayOf(-rotation.m02(), -rotation.m12(), -rotation.m22()),
			CameraConstants.rowMajorOf(motion.clipToPrevClip),
			CameraConstants.rowMajorOf(Matrix4f(motion.clipToPrevClip).invert()),
			frustum[0],
			frustum[1],
			frustum[2],
			frustum[3],
			0f,
			0f,
		)
	}

	private companion object {
		/** No engine target: the frame is evaluated and its output is left where DLSS wrote it. */
		const val NO_DESTINATION = 0L
	}
}
