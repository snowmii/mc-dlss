package me.snowmii.dlss

import java.util.Locale

enum class DlssSessionState {
	DISABLED,
	WAITING_FOR_VULKAN,
	READY,
	FALLBACK_LATCHED,
	CLOSED,
}

enum class DlssFrameRoute {
	VANILLA,
	DLSS,
}

data class DlssFrameDecision(
	val route: DlssFrameRoute,
	val reason: String,
)

enum class DlssNativeStage(val wireName: String) {
	LOAD_LIBRARY("load-library"),
	INITIALIZE("initialize"),
	QUERY_CAPABILITIES("query-capabilities"),
	QUERY_DIMENSIONS("query-dimensions"),
	CONFIGURE("configure"),
	ACQUIRE_IMAGES("acquire-images"),
	RELEASE_IMAGES("release-images"),
	EVALUATE("evaluate"),
	RESET("reset"),
	CLOSE("close"),
}

data class DlssNativeFailure(
	val stage: DlssNativeStage,
	val resultCode: Int,
	val detail: String? = null,
) {
	fun diagnostic(): String {
		val detailSuffix = detail?.takeIf { it.isNotBlank() }?.let { " detail=$it" } ?: ""
		return String.format(Locale.ROOT, "stage=%s result=0x%08X%s", stage.wireName, resultCode, detailSuffix)
	}
}

class DlssSession(
	val config: DlssStartupConfig,
	private val diagnosticSink: (String) -> Unit = {},
) : AutoCloseable {
	var state: DlssSessionState = if (config.enabled) {
		DlssSessionState.WAITING_FOR_VULKAN
	} else {
		DlssSessionState.DISABLED
	}
		private set

	var failure: DlssNativeFailure? = null
		private set

	internal fun markReadyAfterNativeStartup(): Boolean {
		if (state != DlssSessionState.WAITING_FOR_VULKAN) {
			return false
		}

		state = DlssSessionState.READY
		return true
	}

	fun beginFrame(normalInWorldFrame: Boolean, outputDimensions: DlssDimensions): DlssFrameDecision {
		return when {
			state == DlssSessionState.DISABLED -> vanilla("disabled-by-configuration")
			state == DlssSessionState.FALLBACK_LATCHED -> vanilla("latched-fallback")
			state == DlssSessionState.CLOSED -> vanilla("closed")
			state != DlssSessionState.READY -> vanilla("native-not-ready")
			!normalInWorldFrame -> vanilla("unsupported-frame")
			outputDimensions != config.outputDimensions -> vanilla("unsupported-output-size")
			else -> DlssFrameDecision(DlssFrameRoute.DLSS, "normal-world-frame")
		}
	}

	fun latchFailure(failure: DlssNativeFailure): Boolean {
		if (state == DlssSessionState.CLOSED || this.failure != null) {
			return false
		}

		this.failure = failure
		state = DlssSessionState.FALLBACK_LATCHED
		diagnosticSink("DLSS fallback latched: ${failure.diagnostic()}")
		return true
	}

	override fun close() {
		if (state != DlssSessionState.CLOSED) {
			state = DlssSessionState.CLOSED
		}
	}

	private fun vanilla(reason: String): DlssFrameDecision = DlssFrameDecision(DlssFrameRoute.VANILLA, reason)
}
