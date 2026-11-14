package me.snowmii.dlss.streamline

import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.StreamlineException
import me.snowmii.streamline.EvaluationImages
import me.snowmii.streamline.FrameTimings
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.streamline.FgState
import me.snowmii.streamline.FgMultiplier
import me.snowmii.streamline.FgTagRequest
import me.snowmii.streamline.MotionRequest
import me.snowmii.streamline.StreamlineSession
import me.snowmii.streamline.PresentTarget
import me.snowmii.streamline.SrTagRequest
import me.snowmii.streamline.RrPrepareRequest
import me.snowmii.dlss.DlssNativeFailure
import me.snowmii.dlss.DlssNativeStage
import me.snowmii.dlss.DlssSession
import me.snowmii.dlss.DlssSessionState
import me.snowmii.dlss.ReflexMode
import me.snowmii.dlss.SRMode
import me.snowmii.dlss.SRModelPreset
import me.snowmii.dlss.api.RayReconstruction
import me.snowmii.dlss.readout.SessionReadout
import java.nio.file.Path

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

		try {
			val dlssSupported = native.isFeatureSupported(StreamlineSession.FEATURE_DLSS, vkPhysicalDevice) == StreamlineSession.SUCCESS_RESULT
			val fgSupported = native.isFeatureSupported(StreamlineSession.FEATURE_DLSS_G, vkPhysicalDevice) == StreamlineSession.SUCCESS_RESULT
			val rrSupported = native.isFeatureSupported(StreamlineSession.FEATURE_DLSS_RR, vkPhysicalDevice) == StreamlineSession.SUCCESS_RESULT
			val reflexSupported = native.isFeatureSupported(StreamlineSession.FEATURE_REFLEX, vkPhysicalDevice) ==
				StreamlineSession.SUCCESS_RESULT
			SessionReadout.emit("Streamline feature support: DLSS=$dlssSupported, DLSS-G=$fgSupported, " +
				"DLSS-RR=$rrSupported, Reflex=$reflexSupported")
		} catch (_: Throwable) {
		}

		val queriedDimensions = queryDimensions(session.config.qualityMode) ?: return null

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

	override fun reconfigure(qualityMode: SRMode, renderPreset: SRModelPreset): Dimensions? {
		if (session.state != DlssSessionState.READY) {
			return null
		}

		val queriedDimensions = queryDimensions(qualityMode) ?: return null

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

	fun configureFg(numBackBuffers: Int): Boolean {
		if (session.state != DlssSessionState.READY) {
			return false
		}

		return invokeStatus(DlssNativeStage.CONFIGURE) {
			native.configureFg(numBackBuffers)
		}
	}

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

	fun releaseImages(): Boolean = invokeStatus(DlssNativeStage.RELEASE_IMAGES) { native.releaseImages() }

	override fun waitDeviceIdle(): Boolean = invokeStatus(DlssNativeStage.WAIT_DEVICE_IDLE) { native.waitDeviceIdle() }

	fun frameTimings(): FrameTimings? = try {
		native.frameTimings()
	} catch (_: Throwable) {
		null
	}

	fun writeMotion(request: MotionRequest): Boolean {
		if (session.state != DlssSessionState.READY) {
			return false
		}

		val dimensions = renderDimensions ?: return false
		return invokeStatus(DlssNativeStage.WRITE_MOTION) {
			native.writeMotion(MotionRequest(
				request.commandBuffer, request.depth, request.reprojection, request.fgConsumingFrame, dimensions,
			))
		}
	}

	fun tagSrResources(request: SrTagRequest, allowFallback: Boolean = false): Boolean {
		if (session.state != DlssSessionState.READY) {
			return false
		}

		return if (allowFallback) attemptOptionalReconstruction { native.tagSrResources(request) }
		else invokeStatus(DlssNativeStage.TAG) { native.tagSrResources(request) }
	}

	fun prepareRayReconstruction(request: RrPrepareRequest): Boolean {
		if (session.state != DlssSessionState.READY) {
			return false
		}
		return if (request.enabled) attemptOptionalReconstruction { native.prepareRayReconstruction(request) }
		else invokeStatus(DlssNativeStage.CONFIGURE) { native.prepareRayReconstruction(request) }
	}

	fun setExposure(exposure: Float): Boolean {
		if (session.state != DlssSessionState.READY) {
			return false
		}
		return try {
			native.setExposure(exposure) == NATIVE_SUCCESS
		} catch (_: Throwable) {
			false
		}
	}

	fun setTonemapOperator(op: Int): Boolean {
		if (session.state != DlssSessionState.READY) {
			return false
		}
		return try {
			native.setTonemapOperator(op) == NATIVE_SUCCESS
		} catch (_: Throwable) {
			false
		}
	}

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

	fun presentHandoff(): Boolean {
		if (session.state != DlssSessionState.READY) return false
		return invokeStatus(DlssNativeStage.PRESENT_HANDOFF) { native.recordPresentHandoff() }
	}

	fun presentStart(): Boolean = emitMarker { native.presentStart() }

	fun presentEnd(): Boolean = emitMarker { native.presentEnd() }

	fun reflexInputSample(): Boolean = emitMarker { native.reflexInputSample() }

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

		return try {
			native.queryFgState()
		} catch (_: Throwable) {
			null
		}
	}


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

	override fun setReflexMode(mode: ReflexMode): Boolean {
		if (session.state != DlssSessionState.READY) {
			return false
		}
		return try {
			native.setReflexMode(mode.sdkValue) == NATIVE_SUCCESS
		} catch (_: Throwable) {
			false
		}
	}

	override fun queryReflexState(): StreamlineSession.ReflexStateInfo? {
		if (session.state != DlssSessionState.READY) {
			return null
		}
		return try {
			native.queryReflexState()
		} catch (_: Throwable) {
			null
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

	fun presentOutput(destination: PresentTarget): Boolean {
		if (session.state != DlssSessionState.READY) {
			return false
		}

		return invokeStatus(DlssNativeStage.PRESENT_OUTPUT) {
			native.presentOutput(
				PresentTarget(destination.commandBuffer, destination.image, session.outputDimensions, destination.reflection,
					destination.imageView, destination.imageFormat, destination.imageUsage),
			)
		}
	}

	private fun attemptOptionalReconstruction(operation: () -> Int): Boolean = try {
		operation() == NATIVE_SUCCESS
	} catch (_: StreamlineException) {
		false
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

	private fun queryDimensions(mode: SRMode): Dimensions? = invokeDimensions {
		val output = session.outputDimensions
		if (RayReconstruction.enabled) {
			try {
				return@invokeDimensions native.queryOptimalDimensionsRr(output.width, output.height, mode.sdkValue)
			} catch (_: StreamlineException) {
			}
		}
		native.queryOptimalDimensions(output.width, output.height, mode.sdkValue)
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
		private const val NGX_FAIL_BASE = 0xBAD00000.toInt()
		const val FAIL_INVALID_PARAMETER = NGX_FAIL_BASE or 5
	}
}
