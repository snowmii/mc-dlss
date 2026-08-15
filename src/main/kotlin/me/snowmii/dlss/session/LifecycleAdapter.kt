package me.snowmii.dlss.session
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.bridge.NativeException
import me.snowmii.dlss.bridge.DlssEvaluationImages
import me.snowmii.dlss.bridge.DlssFrameTimings
import me.snowmii.dlss.bridge.EvaluationRequest
import me.snowmii.dlss.bridge.FgState
import me.snowmii.dlss.bridge.FgTagRequest
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
	 * Records the DLSS-G per-frame 2x options with the bridge, declaring the swapchain's
	 * back-buffer count.
	 *
	 * The record is fixed at the contract's single multiplier (mode on, one generated frame)
	 * and reads everything else from the configuration the last successful configure stored,
	 * so the bridge checks the ready session and the stored dimensions itself; a failure here
	 * latches the session exactly like any other native stage.
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

	/**
	 * Tags this frame's DLSS-G resources on the caller's command buffer, through Streamline's
	 * frame-based tagging (slGetNewFrameToken + slSetTagForFrame): the engine's render-sized
	 * depth and its output-sized HUD-less colour and UI colour+alpha targets, plus the bridge's
	 * own motion image once it has been acquired. The frame token the tag obtains and retains
	 * is shared with the SR tag for the same frame, and the frame's evaluation consumes it.
	 *
	 * Latched under the same stage name as the SR tag: both are the frame's resource-tag
	 * stage, and a failure in either means the frame's features have no tags to evaluate
	 * against. The stage enum's wire name still says SR; splitting it is a later slice.
	 */
	fun tagFgResources(request: FgTagRequest): Boolean {
		if (session.state != DlssSessionState.READY) {
			return false
		}

		return invokeStatus(DlssNativeStage.TAG) {
			native.tagFgResources(request)
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
		return invokeStatus(DlssNativeStage.PRESENT_HANDOFF) { native.presentHandoff() }
	}

	fun presentStart(): Boolean = session.state == DlssSessionState.READY &&
		invokeStatus(DlssNativeStage.PRESENT_HANDOFF) { native.presentStart() }

	fun presentEnd(): Boolean = session.state == DlssSessionState.READY &&
		invokeStatus(DlssNativeStage.PRESENT_HANDOFF) { native.presentEnd() }

	// The five Reflex/PCL frame markers of the M-12 marker surface: the input sample at
	// Minecraft's GLFW input poll, the simulation pair around runTick's simulation, and the
	// render-submit pair around renderFrame's command-encoder submit. All five are gated on
	// the READY session like the present bracket, and unlike the present bracket they never
	// latch: a marker call failure is the PCL/Reflex diagnostic surface losing a sample,
	// not a frame-route stage failure, and a session that rendered the frame anyway must
	// not degrade because its ping did not reach the plugin. The native side emits each
	// marker under the retained frame token and records it in the reflex-marker oracle only
	// after slPCLSetMarker succeeded, so a refused or failed call is observable through the
	// oracle rather than through the session state.
	fun reflexInputSample(): Boolean = reflexMarker { native.reflexInputSample() }

	fun reflexSimulateStart(): Boolean = reflexMarker { native.reflexSimulateStart() }

	fun reflexSimulateEnd(): Boolean = reflexMarker { native.reflexSimulateEnd() }

	fun reflexRenderSubmitStart(): Boolean = reflexMarker { native.reflexRenderSubmitStart() }

	fun reflexRenderSubmitEnd(): Boolean = reflexMarker { native.reflexRenderSubmitEnd() }

	private fun reflexMarker(operation: () -> Int): Boolean {
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
	fun waitFgInputsIdle(): Boolean {
		if (session.state != DlssSessionState.READY) {
			return false
		}

		val result = try {
			native.waitFgInputsIdle()
		} catch (error: NativeException) {
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

	fun queryFgState(): FgState? {
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

		/** NVSDK_NGX_Result_FAIL_InvalidParameter = NVSDK_NGX_Result_Fail | 5 (0xBAD00000 | 5). */
		const val FAIL_INVALID_PARAMETER = 0xBAD00005.toInt()
	}
}
