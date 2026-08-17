package me.snowmii.dlss.session
import me.snowmii.streamline.Dimensions

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
	WAIT_DEVICE_IDLE("wait-device-idle"),
	WAIT_FG_INPUTS("wait-fg-inputs"),
	WRITE_MOTION("write-motion"),
	TAG("tag-sr-resources"),
	EVALUATE("evaluate"),
	PRESENT_HANDOFF("present-handoff"),
	PRESENT_OUTPUT("present-output"),
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

	/**
	 * Output size this session is configured against right now.
	 *
	 * Seeded from the configuration and then, unless the configuration pinned it, replaced by the
	 * client's real main-target size on the first world frame and on every later change. This is
	 * the size every native call is stamped with, so a reader asking what DLSS is running at asks
	 * here rather than at `config.outputDimensions`, which only records where the session started.
	 */
	var outputDimensions: Dimensions = config.outputDimensions
		private set

	/**
	 * Adopts [dimensions] as the size this session configures against, and reports whether the
	 * caller must now reconfigure the native side.
	 *
	 * A pinned session refuses: `mc.dlss.output-width/height` were named, so frames at any other
	 * size keep routing vanilla, which is the one case the old fixed-size refusal still describes.
	 */
	fun adoptOutputDimensions(dimensions: Dimensions): Boolean {
		if (config.outputPinned || dimensions == outputDimensions) {
			return false
		}
		outputDimensions = dimensions
		return true
	}

	internal fun markReadyAfterNativeStartup(): Boolean {
		if (state != DlssSessionState.WAITING_FOR_VULKAN) {
			return false
		}

		state = DlssSessionState.READY
		return true
	}

	fun beginFrame(normalInWorldFrame: Boolean, outputDimensions: Dimensions): DlssFrameDecision {
		return when {
			state == DlssSessionState.DISABLED -> vanilla("disabled-by-configuration")
			state == DlssSessionState.FALLBACK_LATCHED -> vanilla("latched-fallback")
			state == DlssSessionState.CLOSED -> vanilla("closed")
			state != DlssSessionState.READY -> vanilla("native-not-ready")
			!normalInWorldFrame -> vanilla("unsupported-frame")
			// The session's *current* size, not the configured one: an unpinned session adopts the
			// client's size and reconfigures, so this refusal survives only for the frame that
			// reported a new size before the reconfigure ran, and permanently for a pinned session.
			outputDimensions != this.outputDimensions -> vanilla("unsupported-output-size")
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
