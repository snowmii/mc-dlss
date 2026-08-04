package me.snowmii.dlss

import java.nio.file.Path

/** Coordinates native lifecycle results with one session-latched fallback route. */
class DlssLifecycleAdapter(
	private val session: DlssSession,
	private val native: DlssNativeApi,
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

	fun evaluate(request: DlssEvaluationRequest): Boolean {
		if (session.state != DlssSessionState.READY) {
			return false
		}

		val dimensions = renderDimensions ?: return false
		return invokeStatus(DlssNativeStage.EVALUATE) {
			native.evaluate(
				request.commandBuffer,
				request.colorView,
				request.depthView,
				request.motionView,
				request.outputView,
				dimensions.width,
				dimensions.height,
				session.config.outputDimensions.width,
				session.config.outputDimensions.height,
				request.jitterX,
				request.jitterY,
				request.motionScaleX,
				request.motionScaleY,
				request.frameTimeMilliseconds,
				request.resetHistory,
			)
		}
	}

	private fun invokeStatus(stage: DlssNativeStage, operation: () -> Int): Boolean {
		val result = try {
			operation()
		} catch (error: DlssNativeException) {
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
		} catch (error: DlssNativeException) {
			latch(DlssNativeStage.QUERY_DIMENSIONS, error)
			null
		} catch (error: Throwable) {
			latch(DlssNativeStage.QUERY_DIMENSIONS, error)
			null
		}
	}

	private fun latch(stage: DlssNativeStage, error: Throwable) {
		val failure = if (error is DlssNativeException) {
			DlssNativeFailure(stageFrom(error.stage(), stage), error.resultCode())
		} else {
			DlssNativeFailure(stage, 0, error.message ?: error::class.java.simpleName)
		}
		session.latchFailure(failure)
	}

	private fun stageFrom(wireName: String, fallback: DlssNativeStage): DlssNativeStage =
		DlssNativeStage.values().firstOrNull { it.wireName == wireName } ?: fallback

	private companion object {
		const val NATIVE_SUCCESS = DlssNativeApi.SUCCESS_RESULT
	}
}

data class DlssEvaluationRequest(
	val commandBuffer: Long = 0,
	val colorView: Long = 0,
	val depthView: Long = 0,
	val motionView: Long = 0,
	val outputView: Long = 0,
	val jitterX: Float = 0f,
	val jitterY: Float = 0f,
	val motionScaleX: Float = 0f,
	val motionScaleY: Float = 0f,
	val frameTimeMilliseconds: Float = 0f,
	val resetHistory: Boolean = false,
)
