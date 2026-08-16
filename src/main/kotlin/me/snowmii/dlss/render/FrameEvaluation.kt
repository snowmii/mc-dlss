package me.snowmii.dlss.render
import me.snowmii.dlss.bridge.CameraConstants
import me.snowmii.dlss.bridge.DlssEvaluationImages
import me.snowmii.streamline.FrameTimings
import me.snowmii.dlss.bridge.EvaluationRequest
import me.snowmii.dlss.bridge.FgTagRequest
import me.snowmii.streamline.FgState
import me.snowmii.dlss.bridge.FillVelocityRequest
import me.snowmii.dlss.bridge.ImageBinding
import me.snowmii.dlss.bridge.MotionRequest
import me.snowmii.dlss.bridge.NativeApi
import me.snowmii.dlss.bridge.PresentTarget
import me.snowmii.dlss.bridge.rowMajorOf
import me.snowmii.dlss.bridge.SrTagRequest
import me.snowmii.streamline.Vec2
import me.snowmii.dlss.bridge.VulkanContext
import me.snowmii.dlss.fg.FgSurfacePolicy
import me.snowmii.dlss.mrt.MotionVectorRoute
import me.snowmii.dlss.readout.SessionReadout
import me.snowmii.dlss.session.LifecycleAdapter
import org.joml.Matrix4f
import org.joml.Vector4f
import kotlin.math.abs
import org.lwjgl.vulkan.VkCommandBuffer

/**
 * The engine-owned half of one evaluation: the colour and depth the world phase just rendered.
 *
 * Handles are raw `VkImage` and `VkImageView` values and the formats are raw `VkFormat` values,
 * in the same units the flat native ABI takes them. The motion and output images are the bridge's
 * own and never appear here, which is the whole reason only these two cross.
 *
 * Every image Minecraft's Vulkan backend creates is a single-level, single-layer 2D image, so the
 * subresource ranges are not carried: they are always mip 0, layer 0, one of each.
 */
data class SceneResources(
	val color: ImageBinding,
	val depth: ImageBinding,
)

/**
 * One frame's DLSS-G inputs, in the flat ABI units [SceneResources] already uses.
 *
 * [hudless] is the output-sized HUD-less colour and [ui] the output-sized UI colour+alpha
 * target the frame's FG tags name; the render-sized depth and the motion source come from the
 * scene and the bridge's own images, so only these two cross. Resolved per frame by the
 * runtime's supplier, which names the production main target and UI target - and only when
 * FG is active and both exist at the output size; a null resolution records an SR-only frame.
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
 * Records one frame's DLSS work onto Minecraft's own graphics submission.
 *
 * Everything beneath this class existed and nothing called it: the native bridge could allocate
 * its images, fill the motion image, and evaluate DLSS, and the renderer could route the world
 * into a low-resolution target with coherent jitter and motion - with no path between the two.
 * This is that path, and it is deliberately the only place in the mod that touches a command
 * buffer.
 *
 * The ordering is the contract. All calls go on **one** buffer, motion first, then the frame's
 * resource tags, then the evaluation: the evaluation reads the image the motion pass
 * writes (the pass ends with the barrier that makes those writes visible) and consumes the
 * Streamline frame token the tag call obtained. An FG-active frame composes its DLSS-G
 * record around that chain on the same buffer and token - FG options and FG tag before the
 * SR tag, the SR evaluation, the FG re-tag, and one present handoff - while an inactive
 * frame records SR only and makes no FG calls. The buffer comes from Minecraft's shared
 * command encoder and goes straight back to it, so the work lands behind the world render it
 * consumes and in front of whatever the frame does next. Nothing here submits a queue, signals
 * a fence, or idles the device: the encoder's existing timeline is what orders all of it.
 *
 * A failed stage still hands the buffer back. The native side records its layout restorations
 * whether or not NGX succeeded, so a buffer dropped on the floor is the one outcome that would
 * leave Minecraft an image in a layout its next pass does not expect.
 */
class FrameEvaluation(
	private val adapter: LifecycleAdapter,
	private val context: () -> VulkanContext?,
	/**
	 * The session readout the first-evaluation line is fed to, or null when the evaluation has
	 * no reporting seam - tests and target-only runtimes.
	 */
	private val readout: SessionReadout? = null,
	/**
	 * The FG-mode policy this runtime's swapchain seams read and the controls toggle: an
	 * active policy makes the frame's recording compose DLSS-G around the SR evaluation, and
	 * an inactive one keeps the recording SR-only with no FG calls at all.
	 */
	private val frameGeneration: FgSurfacePolicy = FgSurfacePolicy(),
	/**
	 * The frame's DLSS-G inputs, resolved per frame at recording time, or null to record an
	 * SR-only frame. Production resolves the main target and the UI phase's held target at
	 * the output size; a frame whose UI target does not exist yet - the first frame, or a
	 * resize frame whose held target is stale-sized - resolves null and stays SR-only, which
	 * is safe because a tag naming an image the frame is about to destroy is worse than one
	 * frame without FG.
	 */
	private val fgInputs: () -> FgFrameInputs? = { null },
) : AutoCloseable {
	private var images: DlssEvaluationImages? = null
	private var reportedFirstEvaluation = false

	fun presentStart(): Boolean = adapter.presentStart()
	fun presentEnd(): Boolean = adapter.presentEnd()

	// The Reflex/PCL frame markers of the M-12 surface, delegated straight to the adapter like
	// the present bracket: the input sample at the GLFW poll seam is its own call because it
	// starts the frame, and the simulation and render-submit markers travel as a value, so
	// this seam has one method rather than one per marker. The mixin handlers never touch the
	// adapter directly - they call the world phase, which reaches this object - so the whole
	// marker surface is verifiable off the render thread through this class.
	fun reflexInputSample(): Boolean = adapter.reflexInputSample()
	fun reflexMarker(type: NativeApi.ReflexMarkerType): Boolean = adapter.reflexMarker(type)

	/** The native-owned images this evaluation writes into, or null before the first frame. */
	val evaluationImages: DlssEvaluationImages?
		get() = images

	/**
	 * Records and submits this frame's motion pass and DLSS evaluation.
	 *
	 * Returns true only when both stages recorded successfully. False means the frame produced no
	 * DLSS output and the session has latched whatever failure caused it.
	 *
	 * [route] is the session's world-motion route and [velocity] the scene velocity companion
	 * binding behind it. On [MotionVectorRoute.VELOCITY_MRT] the frame's motion source is that
	 * companion: the post-scene compute fill samples it and the scene depth, merges the
	 * complete field into the native motion image - object vectors copied, camera motion
	 * reconstructed for sentinels - and that image is tagged as the motion source, so a frame
	 * without a companion is skipped rather than evaluated against no motion at all. On
	 * [MotionVectorRoute.CAMERA_ONLY] the existing compute writer and native motion image path
	 * stay exactly as they were, and any carried velocity is ignored.
	 *
	 * [camera] is the frame's camera as the world projection seam sampled it, threaded into
	 * the evaluation's `slSetConstants` so the DLSS-G plugin interpolates the generated
	 * frame's camera from the real one. A null camera records a zero-filled camera; production
	 * always carries one (a frame whose camera was never observed publishes no motion, so
	 * evaluation is skipped before it is reached).
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
		val held = images ?: adapter.acquireImages()?.also { images = it } ?: return false

		val buffer = vulkan.recordCommandBuffer()
		// The FG frame composes its DLSS-G record around the SR evaluation, and only when the
		// policy is active AND the runtime resolved this frame's FG inputs. An inactive frame or
		// one without both output-sized targets records SR-only: no FG options, no FG tags, no
		// handoff. Resolved here rather than inside [record] because the record's two halves
		// straddle the output copy - see [openFgRecord] and [closeFgRecord].
		val fg = if (frameGeneration.active) fgInputs() else null
		val handle = buffer.address()
		// The motion stage opens the recording, ahead of the FG record: it fills the module's
		// motion copy, which the FG tag below names as the frame's motion source.
		val recorded = recordMotion(handle, scene, route, velocity, motion) &&
			openFgRecord(handle, scene, fg) &&
			record(buffer, scene, jitter, motion, camera) &&
			(destinationImage == NO_DESTINATION || present(buffer, destinationImage)) &&
			closeFgRecord(handle, scene, fg)
		// Submitted on every path: see the class comment - an abandoned buffer is what actually
		// breaks the renderer, not a failed evaluation.
		vulkan.submitCommandBuffer(buffer)
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
		if (images == null) {
			return
		}

		images = null
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
	private fun present(buffer: VkCommandBuffer, destinationImage: Long): Boolean = adapter.presentOutput(
		PresentTarget(
			commandBuffer = buffer.address(),
			image = destinationImage,
		),
	)

	private fun record(
		buffer: VkCommandBuffer,
		scene: SceneResources,
		jitter: DlssJitterOffset,
		motion: DlssFrameMotion,
		camera: DlssCameraSample?,
	): Boolean {
		val handle = buffer.address()

		// The frame's SR resources tag between the motion stage and the evaluation, on the same
		// buffer: the tag call obtains the Streamline frame token the evaluation consumes, and
		// the DLSS plugin reads the tagged resources at evaluate time. The motion source is
		// always the native motion image - direct companion tagging is retired - so the tag
		// request is route-independent.
		val tagged = adapter.tagSrResources(
			SrTagRequest(
				commandBuffer = handle,
				color = scene.color,
				depth = scene.depth,
			),
		)
		if (!tagged) {
			return false
		}

		// The two inputs that decide whether DLSS accumulates at all, recorded per evaluated frame
		// so a session that stays aliased while standing still says which one failed.
		readout?.recordFrameJitter(jitter.index, jitter.pixelX, jitter.pixelY, motion.reset)
		val evaluated = adapter.evaluate(
			EvaluationRequest(
				commandBuffer = handle,
				color = scene.color,
				depth = scene.depth,
				// NGX takes the offset in render pixels, which is the unit the sequence is in.
				jitter = Vec2(jitter.pixelX, jitter.pixelY),
				motionScale = Vec2(motion.motionScaleX, motion.motionScaleY),
				frameTimeMilliseconds = motion.frameTimeMillis,
				resetHistory = motion.reset,
				camera = camera?.let { cameraConstants(it, motion) },
			),
		)
		return evaluated
	}

	/**
	 * Records the frame's motion stage, the route's choice, and reports whether it took.
	 *
	 * On VELOCITY_MRT the post-scene fill merges the scene velocity companion into the native
	 * motion image - the sole Streamline motion source - while the compute camera-motion writer
	 * stays retired from this path; on CAMERA_ONLY the compute writer stays exactly as before. A
	 * VELOCITY_MRT frame that reached here without a velocity companion has no motion source at
	 * all and is refused: evaluating with no motion vectors is worse than one frame of the
	 * low-resolution present.
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
				commandBuffer = handle,
				depth = scene.depth,
				velocity = velocity,
				reprojection = FloatArray(16).also { motion.reprojection.get(it) },
				reset = motion.reset,
			),
		)

		MotionVectorRoute.CAMERA_ONLY -> adapter.writeMotion(
			MotionRequest(
				commandBuffer = handle,
				depth = scene.depth,
				reprojection = FloatArray(16).also { motion.reprojection.get(it) },
			),
		)
	}

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
	private fun openFgRecord(handle: Long, scene: SceneResources, fg: FgFrameInputs?): Boolean =
		fg == null ||
			(adapter.configureFg(frameGeneration.declaredBackBuffers) && tagFg(handle, scene, fg))

	/**
	 * The composed frame's closing DLSS-G record: the post-evaluation FG re-tag and the present
	 * handoff, both after the SR output copy.
	 *
	 * The re-tag is the declaration the present path needs - the evaluation's restore has left
	 * every tagged resource in the engine-resting GENERAL layout these tags declare - and it is
	 * also where `tag_fg_resources` records the frame's orientation blits, which is why it sits
	 * after `present` rather than before it.
	 *
	 * Those blits are the frame's DLSS-G snapshot, not a reference the plugin resolves later:
	 * the depth, HUD-less colour, and UI are copied into module-owned images and the plugin
	 * reads the copies at present. So the blit's *timing* decides what DLSS-G interpolates. It
	 * used to run on the opening tag, before `present` copied the SR output into the main
	 * target - so the HUD-less blit captured the main target as it stood at frame start, which
	 * is the *previous* frame's finished image with its whole HUD composited in. DLSS-G was
	 * handed a HUD-less colour that was both a frame stale and not HUD-less, so it read the HUD
	 * as world content, interpolated it, and then recomposited the clean UI over the result.
	 * The crosshair is where that showed first: high contrast, screen-locked, over the
	 * fastest-moving background, and doubled on every generated frame.
	 *
	 * After the output copy the main target holds this frame's world and nothing else - the
	 * screen effects, the 3D crosshair, and the GUI all draw later - so the blit captures
	 * genuine HUD-less colour. `mc_dlss_present_output` returns the main target to the
	 * engine-resting GENERAL layout the blit reads it in, so the copy and the blit compose in
	 * one recording.
	 *
	 * The handoff is then the frame's terminal act, consuming the retained token exactly once so
	 * the next frame's tags advance it.
	 */
	private fun closeFgRecord(handle: Long, scene: SceneResources, fg: FgFrameInputs?): Boolean =
		fg == null || (tagFg(handle, scene, fg) && adapter.presentHandoff())

	/** The frame's four FG tags: shared depth, HUD-less colour, and the UI colour+alpha pair. */
	private fun tagFg(handle: Long, scene: SceneResources, fg: FgFrameInputs): Boolean =
		adapter.tagFgResources(
			FgTagRequest(
				commandBuffer = handle,
				depth = scene.depth,
				hudless = fg.hudless,
				ui = fg.ui,
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
		held: DlssEvaluationImages,
	) {
		if (reportedFirstEvaluation) {
			return
		}

		reportedFirstEvaluation = true
		readout?.firstEvaluation(
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
	 * into [CameraConstants.viewToClip] through [rowMajorOf] unchanged; the clip-to-view
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
			viewToClip = rowMajorOf(camera.projection),
			clipToView = rowMajorOf(Matrix4f(camera.projection).invert()),
			clipToPrevClip = rowMajorOf(motion.clipToPrevClip),
			prevClipToClip = rowMajorOf(Matrix4f(motion.clipToPrevClip).invert()),
			near = frustum[0],
			far = frustum[1],
			fovRadians = frustum[2],
			aspectRatio = frustum[3],
			pos = floatArrayOf(
				camera.cameraX.toFloat(),
				camera.cameraY.toFloat(),
				camera.cameraZ.toFloat(),
			),
			right = floatArrayOf(rotation.m00(), rotation.m10(), rotation.m20()),
			up = floatArrayOf(rotation.m01(), rotation.m11(), rotation.m21()),
			fwd = floatArrayOf(-rotation.m02(), -rotation.m12(), -rotation.m22()),
		)
	}

	private companion object {
		/** No engine target: the frame is evaluated and its output is left where DLSS wrote it. */
		const val NO_DESTINATION = 0L
	}
}
