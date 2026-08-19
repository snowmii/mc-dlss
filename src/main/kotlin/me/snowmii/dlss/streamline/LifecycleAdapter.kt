package me.snowmii.dlss.streamline

import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.StreamlineException
import me.snowmii.streamline.EvaluationImages
import me.snowmii.streamline.FrameTimings
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.streamline.FgState
import me.snowmii.streamline.FgMultiplier
import me.snowmii.streamline.FgTagRequest
import me.snowmii.streamline.FillVelocityRequest
import me.snowmii.streamline.MotionRequest
import me.snowmii.streamline.StreamlineSession
import me.snowmii.streamline.PresentTarget
import me.snowmii.streamline.SrTagRequest
import me.snowmii.dlss.DlssNativeFailure
import me.snowmii.dlss.DlssNativeStage
import me.snowmii.dlss.DlssSession
import me.snowmii.dlss.DlssSessionState
import me.snowmii.dlss.SRMode
import me.snowmii.dlss.SRModelPreset
import java.nio.file.Path

/**
 * Coordinates native lifecycle results with one session-latched fallback route.
 *
 * Also the one place the configured dimensions are stamped onto a request. The bridge checks
 * every recording call against the configuration it was given, and this adapter is what holds
 * that configuration - [renderDimensions] from the last successful configure, and the output
 * size from the session, which is the size the session currently runs at rather than the one it
 * started at (see [DlssSession.outputDimensions]). A caller describing a frame supplies what it can see; the sizes it
 * would have to be told are added here rather than threaded through it.
 */
class LifecycleAdapter(
	private val session: DlssSession,
	private val native: StreamlineSession,
) : SessionBridge {
	private var renderDimensions: Dimensions? = null

	fun initialize(
		vkInstance: Long,
		vkPhysicalDevice: Long,
		vkDevice: Long,
		sdkPath: Path,
		dataPath: Path,
	): Dimensions? {
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
				session.outputDimensions.width,
				session.outputDimensions.height,
				session.config.qualityMode.sdkValue,
			)
		} ?: return null

		if (!invokeStatus(DlssNativeStage.CONFIGURE) {
				native.configureSuperResolution(
					session.outputDimensions.width,
					session.outputDimensions.height,
					queriedDimensions.width,
					queriedDimensions.height,
					session.config.qualityMode.sdkValue,
					session.config.renderPreset.sdkValue,
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
	 * Re-queries render size for a mode/preset while the session is already READY. Does not
	 * re-initialize. Failure latches like any other native stage.
	 */
	override fun reconfigure(qualityMode: SRMode, renderPreset: SRModelPreset): Dimensions? {
		if (session.state != DlssSessionState.READY) {
			return null
		}

		val queriedDimensions = invokeDimensions {
			native.queryOptimalDimensions(
				session.outputDimensions.width,
				session.outputDimensions.height,
				qualityMode.sdkValue,
			)
		} ?: return null

		if (!invokeStatus(DlssNativeStage.CONFIGURE) {
				native.configureSuperResolution(
					session.outputDimensions.width,
					session.outputDimensions.height,
					queriedDimensions.width,
					queriedDimensions.height,
					qualityMode.sdkValue,
					renderPreset.sdkValue,
				)
			}) {
			return null
		}

		renderDimensions = queriedDimensions
		return queriedDimensions
	}

	/**
	 * Records the DLSS-G per-frame options with the bridge, declaring the swapchain's
	 * back-buffer count.
	 *
	 * The record carries the stored multiplier (one generated frame, 2x, by default; the
	 * F12 cycle records another through [setFgMultiplier]) and reads everything else from the
	 * configuration the last successful configure stored, so the bridge checks the ready
	 * session and the stored dimensions itself; a failure here latches the session exactly
	 * like any other native stage.
	 */
	fun configureFg(numBackBuffers: Int): Boolean {
		if (session.state != DlssSessionState.READY) {
			return false
		}

		return invokeStatus(DlssNativeStage.CONFIGURE) {
			native.configureFg(numBackBuffers)
		}
	}

	/**
	 * Re-records the DLSS-G options in the eOff mode with the retained-resources flag, after
	 * the status latch decided the plugin's own state is not usable.
	 *
	 * Deliberately non-latching: the status latch must leave the SR session READY - the whole
	 * fallback is SR-only, not vanilla - so a refused or failed eOff record must not send the
	 * session to FALLBACK_LATCHED. The failure is instead invisible to the session: the policy
	 * has already stopped composing FG frames, and the diagnostic the runtime emits is the
	 * latch's one exact line. The bridge answers FAIL_NotInitialized without a ready session
	 * and FAIL_InvalidParameter while no DLSS-G options record is stored, the same gates as
	 * the FG tag.
	 */
	override fun recordFrameGenerationOff(): Boolean {
		if (session.state != DlssSessionState.READY) {
			return false
		}
		return try {
			native.setFgMode(0) == NATIVE_SUCCESS
		} catch (_: Throwable) {
			false
		}
	}

	/**
	 * Returns the native-owned motion and output images, or null when acquisition failed.
	 *
	 * A failure here latches the session exactly like any other native stage, because a session
	 * that cannot allocate the images DLSS writes into has nothing left to try.
	 */
	fun acquireImages(): EvaluationImages? {
		if (session.state != DlssSessionState.READY) {
			return null
		}

		return try {
			native.acquireImages()
		} catch (error: StreamlineException) {
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
	override fun waitDeviceIdle(): Boolean = invokeStatus(DlssNativeStage.WAIT_DEVICE_IDLE) { native.waitDeviceIdle() }

	/**
	 * GPU timings of the last frame that completed every recorded stage, or null when there is no
	 * measurement yet.
	 *
	 * Deliberately outside the latching path: a missing measurement is a diagnostic that has not
	 * arrived, and a session that stopped rendering DLSS because its profiler had nothing to say
	 * would be a worse bug than the one this is here to find.
	 */
	fun frameTimings(): FrameTimings? = try {
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
			native.writeMotion(MotionRequest(request.commandBuffer, request.depth, request.reprojection, dimensions))
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
			native.fillVelocity(
				FillVelocityRequest(request.commandBuffer, request.depth, request.velocity, request.reprojection, request.reset, dimensions),
			)
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
	fun tagSrResources(request: SrTagRequest): Boolean {
		if (session.state != DlssSessionState.READY) {
			return false
		}

		return invokeStatus(DlssNativeStage.TAG) {
			native.tagSrResources(request)
		}
	}

	/**
	 * Tags this frame's DLSS-G resources on the caller's command buffer, through Streamline's
	 * frame-based tagging (slGetNewFrameToken + slSetTagForFrame): the engine's render-sized
	 * depth and its output-sized HUD-less colour and UI colour+alpha targets, plus the bridge's
	 * own motion image once it has been acquired. The frame token the tag obtains and retains
	 * is shared with the SR tag for the same frame, and the frame's evaluation consumes it.
	 *
	 * Latched under the same stage name as the SR tag: both are the frame's resource-tag
	 * stage, and a failure in either means the frame's features have no tags to evaluate
	 * against. The stage enum keeps its existing wire name for ABI compatibility.
	 */
	fun tagFgResources(request: FgTagRequest): Boolean {
		if (session.state != DlssSessionState.READY) {
			return false
		}

		return invokeStatus(DlssNativeStage.TAG) {
			native.tagFrameGenerationResources(request)
		}
	}

	fun evaluate(request: EvaluationRequest): Boolean {
		if (session.state != DlssSessionState.READY) {
			return false
		}

		val dimensions = renderDimensions ?: return false
		return invokeStatus(DlssNativeStage.EVALUATE) {
			native.evaluateSuperResolution(
				EvaluationRequest.builder()
					.commandBuffer(request.commandBuffer)
					.color(request.color)
					.depth(request.depth)
					.jitter(request.jitter)
					.motionScale(request.motionScale)
					.frameTimeMilliseconds(request.frameTimeMilliseconds)
					.resetHistory(request.resetHistory)
					.renderDimensions(dimensions)
					.camera(request.camera)
					.build(),
			)
		}
	}

	/**
	 * Records the frame's present-handoff eligibility with the bridge: re-records the stored
	 * DLSS-G 2x options with the back-buffer count the last successful [configureFg]
	 * declared, and accepts exactly one complete current-frame SR+FG tag set under equal
	 * frame indexes.
	 *
	 * Missing options, partial tags, and consumed eligibility are refused without side
	 * effects, and a refusal here latches the session exactly like any other native stage -
	 * a frame that cannot hand off must not present as if it could. The call records no GPU
	 * work and owns no command buffer.
	 */
	fun presentHandoff(): Boolean {
		if (session.state != DlssSessionState.READY) return false
		return invokeStatus(DlssNativeStage.PRESENT_HANDOFF) { native.recordPresentHandoff() }
	}

	fun presentStart(): Boolean = emitMarker { native.presentStart() }

	fun presentEnd(): Boolean = emitMarker { native.presentEnd() }

	// Reflex/PCL markers: GLFW input sample, runTick simulation pair, renderFrame submit pair,
	// and the present bracket around queue present. Gated on READY. Failures never latch — a
	// missed overlay ping must not degrade a frame that already rendered. Oracle records only
	// after slPCLSetMarker succeeds.
	fun reflexInputSample(): Boolean = emitMarker { native.reflexInputSample() }

	/**
	 * Emits one of the four simulation/render-submit markers. The marker is a value the whole
	 * way down to the ABI, so this seam does not grow a method per marker;
	 * [StreamlineSession.ReflexMarkerType.INPUT_SAMPLE] is refused natively because that seam is
	 * [reflexInputSample], which obtains the frame's token and sleeps before it emits.
	 */
	fun reflexMarker(type: StreamlineSession.ReflexMarkerType): Boolean =
		emitMarker { native.reflexMarker(type) }

	private fun emitMarker(operation: () -> Int): Boolean {
		if (session.state != DlssSessionState.READY) {
			return false
		}
		return try {
			operation() == NATIVE_SUCCESS
		} catch (_: Throwable) {
			false
		}
	}

	/**
	 * Blocks until Streamline's DLSS-G input processing for the previously presented frame has
	 * completed, on the caller's (present/render) thread and through the Vulkan device.
	 *
	 * The DLSS-G options record the eBlockNoClientQueues queue-parallelism mode, under which
	 * the plugin reads the tagged inputs of a presented frame on its own queues after Present;
	 * the guide requires the host to wait on the completion fence the bridge reads via
	 * slDLSSGGetState before it modifies or destroys those inputs in a later frame. The runtime
	 * calls this at the start of an FG-active frame, before the world phase rewrites the tagged
	 * depth, motion, HUD-less, and UI inputs.
	 *
	 * One refusal is expected in production and is benign: while no DLSS-G options have
	 * recorded yet - the first FG frame, or the first frame after FG switched back on - the
	 * bridge answers FAIL_InvalidParameter, and there is nothing to wait for because no frame
	 * has been presented through DLSS-G (a configuration replacement, whose frames also find
	 * the options invalidated, already stalled the device through [waitDeviceIdle]). Every
	 * other failure latches the session exactly like any other native stage, and the routing
	 * decision reads the latched state.
	 */
	override fun waitFgInputsIdle(): Boolean {
		if (session.state != DlssSessionState.READY) {
			return false
		}

		val result = try {
			native.waitFgInputsIdle()
		} catch (error: StreamlineException) {
			latch(DlssNativeStage.WAIT_FG_INPUTS, error)
			return false
		} catch (error: Throwable) {
			latch(DlssNativeStage.WAIT_FG_INPUTS, error)
			return false
		}

		return when (result) {
			NATIVE_SUCCESS -> true
			FAIL_INVALID_PARAMETER -> true
			else -> {
				session.latchFailure(DlssNativeFailure(DlssNativeStage.WAIT_FG_INPUTS, result))
				false
			}
		}
	}

	override fun queryFgState(): FgState? {
		if (session.state != DlssSessionState.READY) {
			return null
		}

		// Read-only window onto the plugin, never a session stage: a query that fails must not
		// latch the session, it just shows the monitor nothing.
		return try {
			native.queryFgState()
		} catch (_: Throwable) {
			null
		}
	}


	/**
	 * Reads the stored FG multiplier and the device's numFramesToGenerateMax through the
	 * bridge: the cycle's current value and its ceiling/wrap point.
	 *
	 * Same non-latching read-only posture as [queryFgState]: a session that cannot answer
	 * (not READY, no options recorded, or a failed query) answers null and the cycle simply
	 * does not run.
	 */
	override fun queryFgMultiplier(): FgMultiplier? {
		if (session.state != DlssSessionState.READY) {
			return null
		}
		return try {
			native.queryFgMultiplier()
		} catch (_: Throwable) {
			null
		}
	}

	/**
	 * Records a new FG multiplier with the bridge, validated natively against the device
	 * ceiling.
	 *
	 * Deliberately non-latching like [recordFrameGenerationOff]: a refused record (the device does
	 * not support the value, or the session cannot answer) keeps the current multiplier in
	 * effect and must not send the SR session to FALLBACK_LATCHED - the caller's readout
	 * then reports the multiplier actually in effect, which is the contract's "a refusal
	 * changes nothing" half.
	 */
	/**
	 * Records the Reflex frame-rate cap. Never latches: a cap the plugin refuses is a session
	 * that keeps Minecraft's own limiter, which is what the false answer tells the caller, not a
	 * frame-route stage failure.
	 */
	override fun recordReflexFrameLimit(microsecondsPerFrame: Int): Boolean {
		if (session.state != DlssSessionState.READY) {
			return false
		}
		return try {
			native.recordReflexFrameLimit(microsecondsPerFrame) == NATIVE_SUCCESS
		} catch (_: Throwable) {
			false
		}
	}

	override fun setFgMultiplier(numFramesToGenerate: Int): Boolean {
		if (session.state != DlssSessionState.READY) {
			return false
		}
		return try {
			native.setFgMultiplier(numFramesToGenerate) == NATIVE_SUCCESS
		} catch (_: Throwable) {
			false
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
				PresentTarget(destination.commandBuffer, destination.image, session.outputDimensions),
			)
		}
	}

	private fun invokeStatus(stage: DlssNativeStage, operation: () -> Int): Boolean {
		val result = try {
			operation()
		} catch (error: StreamlineException) {
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

	private fun invokeDimensions(operation: () -> Dimensions): Dimensions? {
		return try {
			operation()
		} catch (error: StreamlineException) {
			latch(DlssNativeStage.QUERY_DIMENSIONS, error)
			null
		} catch (error: Throwable) {
			latch(DlssNativeStage.QUERY_DIMENSIONS, error)
			null
		}
	}

	private fun latch(stage: DlssNativeStage, error: Throwable) {
		val failure = if (error is StreamlineException) {
			DlssNativeFailure(stageFrom(error.stage(), stage), error.resultCode())
		} else {
			DlssNativeFailure(stage, 0, error.message ?: error::class.java.simpleName)
		}
		session.latchFailure(failure)
	}

	private fun stageFrom(wireName: String, fallback: DlssNativeStage): DlssNativeStage =
		DlssNativeStage.entries.firstOrNull { it.wireName == wireName } ?: fallback

	private companion object {
		const val NATIVE_SUCCESS = StreamlineSession.SUCCESS_RESULT

		/** NVSDK_NGX_Result_FAIL_InvalidParameter = NVSDK_NGX_Result_Fail | 5 (0xBAD00000 | 5). */
		const val FAIL_INVALID_PARAMETER = 0xBAD00005.toInt()
	}
}
