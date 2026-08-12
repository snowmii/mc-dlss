package me.snowmii.dlss.session
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.bridge.NativeException
import me.snowmii.dlss.bridge.DlssEvaluationImages
import me.snowmii.dlss.bridge.DlssFrameTimings
import me.snowmii.dlss.bridge.EvaluationRequest
import me.snowmii.dlss.bridge.FillVelocityRequest
import me.snowmii.dlss.bridge.MotionRequest
import me.snowmii.dlss.bridge.NativeApi
import me.snowmii.dlss.bridge.PresentTarget
import me.snowmii.dlss.bridge.SrTagRequest
import java.nio.file.Path

/**
 * Coordinates native lifecycle results with one session-latched fallback route.
 *
 * Also the one place the configured dimensions are stamped onto a request. The bridge checks
 * every recording call against the configuration it was given, and this adapter is what holds
 * that configuration - [renderDimensions] from the last successful configure, and the output
 * size from the session. A caller describing a frame supplies what it can see; the sizes it
 * would have to be told are added here rather than threaded through it.
 */
class LifecycleAdapter(
	private val session: DlssSession,
	private val native: NativeApi,
) {
	private var renderDimensions: DlssDimensions? = null

	fun initialize(
		vkInstance: Long,
		vkPhysicalDevice: Long,
		vkDevice: Long,
		sdkPath: Path,
		dataPath: Path,
	): DlssDimensions? {
		if (session.state != DlssSessionState.WAITING_FOR_VULKAN) {
			return null
		}

		if (!invokeStatus(DlssNativeStage.INITIALIZE) {
				native.initialize(vkInstance, vkPhysicalDevice, vkDevice, sdkPath, dataPath)
			}) {
			return null
		}

		val queriedDimensions = invokeDimensions {
			native.queryOptimalDimensions(
				session.config.outputDimensions.width,
				session.config.outputDimensions.height,
				session.config.qualityMode.ngxValue,
			)
		} ?: return null

		if (!invokeStatus(DlssNativeStage.CONFIGURE) {
				native.configure(
					session.config.outputDimensions.width,
					session.config.outputDimensions.height,
					queriedDimensions.width,
					queriedDimensions.height,
					session.config.qualityMode.ngxValue,
					session.config.renderPreset.ngxValue,
				)
			}) {
			return null
		}

		renderDimensions = queriedDimensions
		if (!session.markReadyAfterNativeStartup()) {
			renderDimensions = null
			return null
		}
		return queriedDimensions
	}

	/**
	 * Re-queries and re-stores the native configuration for a mode and preset chosen while the
	 * session is already running, returning the new render dimensions or null when it failed.
	 *
	 * This is [initialize] without the initialization: NGX is already up and the device is
	 * unchanged, so what a mode change actually needs is the render size that mode implies and a
	 * configuration the next feature creation will disagree with. A failure latches the session
	 * exactly like any other native stage - a session whose mode change was refused knows nothing
	 * about what it is now configured to.
	 */
	fun reconfigure(qualityMode: SRMode, renderPreset: SRModelPreset): DlssDimensions? {
		if (session.state != DlssSessionState.READY) {
			return null
		}

		val queriedDimensions = invokeDimensions {
			native.queryOptimalDimensions(
				session.config.outputDimensions.width,
				session.config.outputDimensions.height,
				qualityMode.ngxValue,
			)
		} ?: return null

		if (!invokeStatus(DlssNativeStage.CONFIGURE) {
				native.configure(
					session.config.outputDimensions.width,
					session.config.outputDimensions.height,
					queriedDimensions.width,
					queriedDimensions.height,
					qualityMode.ngxValue,
					renderPreset.ngxValue,
				)
			}) {
			return null
		}

		renderDimensions = queriedDimensions
		return queriedDimensions
	}

	/**
	 * Returns the native-owned motion and output images, or null when acquisition failed.
	 *
	 * A failure here latches the session exactly like any other native stage, because a session
	 * that cannot allocate the images DLSS writes into has nothing left to try.
	 */
	fun acquireImages(): DlssEvaluationImages? {
		if (session.state != DlssSessionState.READY) {
			return null
		}

		return try {
			native.acquireImages()
		} catch (error: NativeException) {
			latch(DlssNativeStage.ACQUIRE_IMAGES, error)
			null
		} catch (error: Throwable) {
			latch(DlssNativeStage.ACQUIRE_IMAGES, error)
			null
		}
	}

	/** Releases the native-owned images. Safe to call when none are allocated. */
	fun releaseImages(): Boolean = invokeStatus(DlssNativeStage.RELEASE_IMAGES) { native.releaseImages() }

	/**
	 * Blocks until the device has finished every frame already submitted to it.
	 *
	 * Unlike every other call here this is not gated on a READY session: it is what makes releasing
	 * GPU objects safe, and a session that has just latched a failure is releasing them too. A
	 * device that cannot be waited on has been lost already, so the failure is latched and the
	 * caller releases anyway - there is nothing left in flight to protect.
	 */
	fun waitDeviceIdle(): Boolean = invokeStatus(DlssNativeStage.WAIT_DEVICE_IDLE) { native.waitDeviceIdle() }

	/**
	 * GPU timings of the last frame that completed every recorded stage, or null when there is no
	 * measurement yet.
	 *
	 * Deliberately outside the latching path: a missing measurement is a diagnostic that has not
	 * arrived, and a session that stopped rendering DLSS because its profiler had nothing to say
	 * would be a worse bug than the one this is here to find.
	 */
	fun frameTimings(): DlssFrameTimings? = try {
		native.frameTimings()
	} catch (_: Throwable) {
		null
	}

	/**
	 * Records the camera-only motion pass that fills the native motion image, on the caller's
	 * command buffer.
	 *
	 * This has to precede [evaluate] on the same buffer: the evaluation reads the image this pass
	 * writes, and the pass ends with a barrier making its writes visible to it.
	 */
	fun writeMotion(request: MotionRequest): Boolean {
		if (session.state != DlssSessionState.READY) {
			return false
		}

		val dimensions = renderDimensions ?: return false
		return invokeStatus(DlssNativeStage.WRITE_MOTION) {
			native.writeMotion(request.copy(renderDimensions = dimensions))
		}
	}

	/**
	 * Records the post-scene velocity merge on the caller's command buffer: one dispatch samples
	 * the engine's depth image and its sparse RG16_FLOAT velocity companion, copies every
	 * non-sentinel object vector unchanged and reconstructs jitter-stripped camera motion for
	 * every sentinel pixel, and writes the complete merged field into the native motion image.
	 * On a reset frame the dispatch writes the invalid sentinel everywhere instead.
	 *
	 * This has to precede [tagSrResources] on the same buffer: the native motion image is the
	 * sole Streamline motion source, and the evaluation reads it.
	 *
	 * Latched under the same stage name as the compute writer: both are the frame's motion
	 * stage, and a failure in either means the frame has no motion source.
	 */
	fun fillVelocity(request: FillVelocityRequest): Boolean {
		if (session.state != DlssSessionState.READY) {
			return false
		}

		val dimensions = renderDimensions ?: return false
		return invokeStatus(DlssNativeStage.WRITE_MOTION) {
			native.fillVelocity(request.copy(renderDimensions = dimensions))
		}
	}

	/**
	 * Tags this frame's SR resources on the caller's command buffer, through Streamline's
	 * frame-based tagging (slGetNewFrameToken + slSetTagForFrame), and retains the frame token
	 * the evaluation consumes.
	 *
	 * This has to precede [evaluate] on the same buffer: the evaluation records Streamline's
	 * constants and feature evaluation against the token this call obtained, and evaluating
	 * with no retained token fails.
	 */
	fun tagSrResources(request: SrTagRequest): Boolean {		if (session.state != DlssSessionState.READY) {
			return false
		}

		return invokeStatus(DlssNativeStage.TAG) {
			native.tagSrResources(request)
		}
	}

	fun evaluate(request: EvaluationRequest): Boolean {
		if (session.state != DlssSessionState.READY) {
			return false
		}

		val dimensions = renderDimensions ?: return false
		return invokeStatus(DlssNativeStage.EVALUATE) {
			native.evaluate(request.copy(renderDimensions = dimensions))
		}
	}

	/**
	 * Records the copy of the upscaled output into [destination], on the caller's command buffer.
	 *
	 * The destination size is the session's configured output, not a parameter: the copy is the
	 * step that makes the upscaled frame visible, and a destination of any other size means the
	 * caller and the configuration disagree about what "output resolution" is.
	 */
	fun presentOutput(destination: PresentTarget): Boolean {
		if (session.state != DlssSessionState.READY) {
			return false
		}

		return invokeStatus(DlssNativeStage.PRESENT_OUTPUT) {
			native.presentOutput(
				destination.copy(outputDimensions = session.config.outputDimensions),
			)
		}
	}

	private fun invokeStatus(stage: DlssNativeStage, operation: () -> Int): Boolean {
		val result = try {
			operation()
		} catch (error: NativeException) {
			latch(stage, error)
			return false
		} catch (error: Throwable) {
			latch(stage, error)
			return false
		}

		if (result == NATIVE_SUCCESS) {
			return true
		}

		session.latchFailure(DlssNativeFailure(stage, result))
		return false
	}

	private fun invokeDimensions(operation: () -> DlssDimensions): DlssDimensions? {
		return try {
			operation()
		} catch (error: NativeException) {
			latch(DlssNativeStage.QUERY_DIMENSIONS, error)
			null
		} catch (error: Throwable) {
			latch(DlssNativeStage.QUERY_DIMENSIONS, error)
			null
		}
	}

	private fun latch(stage: DlssNativeStage, error: Throwable) {
		val failure = if (error is NativeException) {
			DlssNativeFailure(stageFrom(error.stage(), stage), error.resultCode())
		} else {
			DlssNativeFailure(stage, 0, error.message ?: error::class.java.simpleName)
		}
		session.latchFailure(failure)
	}

	private fun stageFrom(wireName: String, fallback: DlssNativeStage): DlssNativeStage =
		DlssNativeStage.entries.firstOrNull { it.wireName == wireName } ?: fallback

	private companion object {
		const val NATIVE_SUCCESS = NativeApi.SUCCESS_RESULT
	}
}
