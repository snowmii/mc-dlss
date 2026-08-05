package me.snowmii.dlss

import me.snowmii.McDlss
import org.slf4j.LoggerFactory

/**
 * Client-side holder for the one world phase the render-loop mixins share.
 *
 * The mixins cannot own this: `GameRenderer.mainRenderTarget()` is called from everywhere and
 * must stay allocation-free and side-effect-free, while `LevelRenderer.render` is the only
 * place allowed to start the DLSS path. So [worldPhase] initializes at most once, from the
 * render loop, and [activeWorldPhase] is the cheap read every other seam uses.
 *
 * Native library loading happens here rather than at mod init because a failure has to degrade
 * to vanilla instead of killing the client: it is latched on the session and never retried,
 * exactly like a failed native stage.
 */
object DlssClientRuntime {
	private val LOGGER = LoggerFactory.getLogger(McDlss.MOD_ID)

	@Volatile
	private var phase: DlssWorldPhase? = null
	private var initialized = false

	/** The world phase once it exists, without ever creating it. */
	@JvmStatic
	fun activeWorldPhase(): DlssWorldPhase? = phase

	/** The world phase, initializing it on the first call. Render loop only. */
	@JvmStatic
	fun worldPhase(): DlssWorldPhase? {
		if (initialized) {
			return phase
		}

		initialized = true
		val session = McDlss.session
		if (!session.config.enabled) {
			return null
		}

		phase = try {
			val native = DlssNative.open(DlssExtensionBootstrap.nativeLibrary())
			DlssWorldPhase.forMinecraft(DlssRenderRuntime.forMinecraft(session, native))
		} catch (error: Throwable) {
			LOGGER.warn("DLSS native bridge unavailable; every frame renders vanilla", error)
			session.latchFailure(DlssNativeFailure(DlssNativeStage.LOAD_LIBRARY, 0, error.toString()))
			null
		}
		return phase
	}
}
