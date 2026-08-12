package me.snowmii.dlss.render
import me.snowmii.dlss.bridge.DlssEvaluationImages
import me.snowmii.dlss.bridge.DlssFrameTimings
import me.snowmii.dlss.bridge.EvaluationRequest
import me.snowmii.dlss.bridge.FillVelocityRequest
import me.snowmii.dlss.bridge.ImageBinding
import me.snowmii.dlss.bridge.MotionRequest
import me.snowmii.dlss.bridge.PresentTarget
import me.snowmii.dlss.bridge.SrTagRequest
import me.snowmii.dlss.bridge.Vec2
import me.snowmii.dlss.bridge.VulkanContext
import me.snowmii.dlss.bridge.VulkanContextRegistry
import me.snowmii.dlss.mrt.MotionVectorRoute
import me.snowmii.dlss.readout.SessionReadout
import me.snowmii.dlss.session.LifecycleAdapter
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
 * Records one frame's DLSS work onto Minecraft's own graphics submission.
 *
 * Everything beneath this class existed and nothing called it: the native bridge could allocate
 * its images, fill the motion image, and evaluate DLSS, and the renderer could route the world
 * into a low-resolution target with coherent jitter and motion - with no path between the two.
 * This is that path, and it is deliberately the only place in the mod that touches a command
 * buffer.
 *
 * The ordering is the contract. All three calls go on **one** buffer, motion first, then the
 * frame's resource tags, then the evaluation: the evaluation reads the image the motion pass
 * writes (the pass ends with the barrier that makes those writes visible) and consumes the
 * Streamline frame token the tag call obtained. The buffer comes from Minecraft's shared
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
) : AutoCloseable {
	private var images: DlssEvaluationImages? = null
	private var reportedFirstEvaluation = false

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
	 */
	fun evaluateFrame(
		scene: SceneResources,
		jitter: DlssJitterOffset,
		motion: DlssFrameMotion,
		destinationImage: Long = NO_DESTINATION,
		route: MotionVectorRoute = MotionVectorRoute.CAMERA_ONLY,
		velocity: ImageBinding? = null,
	): Boolean {
		val vulkan = context() ?: return false
		val held = images ?: adapter.acquireImages()?.also { images = it } ?: return false

		val buffer = vulkan.recordCommandBuffer()
		val recorded = record(buffer, scene, jitter, motion, held, route, velocity) &&
			(destinationImage == NO_DESTINATION || present(buffer, destinationImage))
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
	fun sampleTimings(): DlssFrameTimings? = adapter.frameTimings()

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
		held: DlssEvaluationImages,
		route: MotionVectorRoute,
		velocity: ImageBinding?,
	): Boolean {
		val handle = buffer.address()

		// The motion stage is the route's choice. On VELOCITY_MRT the post-scene fill merges the
		// scene velocity companion into the native motion image - the sole Streamline motion
		// source - while the compute camera-motion writer stays retired from this path; on
		// CAMERA_ONLY the compute writer stays exactly as before. A VELOCITY_MRT frame that
		// reached here without a velocity companion has no motion source at all and is skipped:
		// evaluating with no motion vectors is worse than one frame of the low-resolution
		// present.
		val motionRecorded = when (route) {
			MotionVectorRoute.VELOCITY_MRT -> {
				if (velocity == null) {
					return false
				}
				// The fill comes first in the frame's recording, before the tag: it opens the
				// native timing chain with a real motion stage, and the tag must find the native
				// motion image already merged so the motion it names is complete.
				adapter.fillVelocity(
					FillVelocityRequest(
						commandBuffer = handle,
						depth = scene.depth,
						velocity = velocity,
						reprojection = FloatArray(16).also { motion.reprojection.get(it) },
						reset = motion.reset,
					),
				)
			}

			MotionVectorRoute.CAMERA_ONLY -> adapter.writeMotion(
				MotionRequest(
					commandBuffer = handle,
					depth = scene.depth,
					reprojection = FloatArray(16).also { motion.reprojection.get(it) },
				),
			)
		}
		if (!motionRecorded) {
			return false
		}

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

		return adapter.evaluate(
			EvaluationRequest(
				commandBuffer = handle,
				color = scene.color,
				depth = scene.depth,
				// NGX takes the offset in render pixels, which is the unit the sequence is in.
				jitter = Vec2(jitter.pixelX, jitter.pixelY),
				motionScale = Vec2(motion.motionScaleX, motion.motionScaleY),
				frameTimeMilliseconds = motion.frameTimeMillis,
				resetHistory = motion.reset,
			),
		)
	}

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

	private companion object {
		/** No engine target: the frame is evaluated and its output is left where DLSS wrote it. */
		const val NO_DESTINATION = 0L
	}
}
