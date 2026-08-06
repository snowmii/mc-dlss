package me.snowmii.dlss

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
data class DlssSceneResources(
	val colorView: Long,
	val colorImage: Long,
	val colorFormat: Int,
	val depthView: Long,
	val depthImage: Long,
	val depthFormat: Int,
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
 * The ordering is the contract. Both calls go on **one** buffer, motion first, because the
 * evaluation reads the image the motion pass writes and the pass ends with the barrier that makes
 * those writes visible. The buffer comes from Minecraft's shared command encoder and goes straight
 * back to it, so the work lands behind the world render it consumes and in front of whatever the
 * frame does next. Nothing here submits a queue, signals a fence, or idles the device: the
 * encoder's existing timeline is what orders all of it.
 *
 * A failed stage still hands the buffer back. The native side records its layout restorations
 * whether or not NGX succeeded, so a buffer dropped on the floor is the one outcome that would
 * leave Minecraft an image in a layout its next pass does not expect.
 */
class DlssFrameEvaluation(
	private val adapter: DlssLifecycleAdapter,
	private val context: () -> DlssVulkanContext?,
	private val diagnostics: (String) -> Unit = {},
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
	 */
	fun evaluateFrame(
		scene: DlssSceneResources,
		jitter: DlssJitterOffset,
		motion: DlssFrameMotion,
		destinationImage: Long = NO_DESTINATION,
	): Boolean {
		val vulkan = context() ?: return false
		val held = images ?: adapter.acquireImages()?.also { images = it } ?: return false

		val buffer = vulkan.recordCommandBuffer()
		val recorded = record(buffer, scene, jitter, motion, held) &&
			(destinationImage == NO_DESTINATION || present(buffer, destinationImage))
		// Submitted on every path: see the class comment - an abandoned buffer is what actually
		// breaks the renderer, not a failed evaluation.
		vulkan.submitCommandBuffer(buffer)
		reportFirstEvaluation(recorded, scene, held)
		return recorded
	}

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
		DlssPresentTarget(
			commandBuffer = buffer.address(),
			image = destinationImage,
			aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
			levelCount = 1,
			layerCount = 1,
		),
	)

	private fun record(
		buffer: VkCommandBuffer,
		scene: DlssSceneResources,
		jitter: DlssJitterOffset,
		motion: DlssFrameMotion,
		held: DlssEvaluationImages,
	): Boolean {
		val handle = buffer.address()
		val wroteMotion = adapter.writeMotion(
			DlssMotionRequest(
				commandBuffer = handle,
				depthView = scene.depthView,
				depthImage = scene.depthImage,
				depthFormat = scene.depthFormat,
				depthAspectMask = VK_IMAGE_ASPECT_DEPTH_BIT,
				depthLevelCount = 1,
				depthLayerCount = 1,
				reprojection = FloatArray(16).also { motion.reprojection.get(it) },
			),
		)
		if (!wroteMotion) {
			return false
		}

		return adapter.evaluate(
			DlssEvaluationRequest(
				commandBuffer = handle,
				colorView = scene.colorView,
				colorImage = scene.colorImage,
				colorFormat = scene.colorFormat,
				colorAspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
				colorLevelCount = 1,
				colorLayerCount = 1,
				depthView = scene.depthView,
				depthImage = scene.depthImage,
				depthFormat = scene.depthFormat,
				depthAspectMask = VK_IMAGE_ASPECT_DEPTH_BIT,
				depthLevelCount = 1,
				depthLayerCount = 1,
				motionView = held.motionView,
				motionImage = held.motionImage,
				motionFormat = held.motionFormat,
				motionAspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
				motionLevelCount = 1,
				motionLayerCount = 1,
				outputView = held.outputView,
				outputImage = held.outputImage,
				outputFormat = held.outputFormat,
				outputAspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
				outputLevelCount = 1,
				outputLayerCount = 1,
				// NGX takes the offset in render pixels, which is the unit the sequence is in.
				jitterX = jitter.pixelX,
				jitterY = jitter.pixelY,
				motionScaleX = motion.motionScaleX,
				motionScaleY = motion.motionScaleY,
				frameTimeMilliseconds = motion.frameTimeMillis,
				resetHistory = motion.reset,
			),
		)
	}

	/**
	 * Reports the first evaluation exactly once.
	 *
	 * A recorded evaluation and a session that silently never reached one look identical from
	 * outside - the frame renders either way. The line names which stage the frame actually got
	 * through and the images it wrote into, which is enough to tell them apart from the log alone.
	 */
	private fun reportFirstEvaluation(
		recorded: Boolean,
		scene: DlssSceneResources,
		held: DlssEvaluationImages,
	) {
		if (reportedFirstEvaluation) {
			return
		}

		reportedFirstEvaluation = true
		diagnostics(
			"DLSS first evaluation: recorded=$recorded" +
				" color=0x${scene.colorImage.toString(16)}" +
				" depth=0x${scene.depthImage.toString(16)}" +
				" motion=0x${held.motionImage.toString(16)}" +
				" output=0x${held.outputImage.toString(16)}",
		)
	}

	private companion object {
		/** No engine target: the frame is evaluated and its output is left where DLSS wrote it. */
		const val NO_DESTINATION = 0L

		/** Raw `VkImageAspectFlagBits`; every Minecraft target is one plain colour or depth image. */
		const val VK_IMAGE_ASPECT_COLOR_BIT = 0x1
		const val VK_IMAGE_ASPECT_DEPTH_BIT = 0x2
	}
}
