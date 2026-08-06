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

	@Volatile
	private var controls: DlssRuntimeControls? = null
	private var initialized = false

	/** The world phase once it exists, without ever creating it. */
	@JvmStatic
	fun activeWorldPhase(): DlssWorldPhase? = phase

	/**
	 * The reviewer's controls once the render loop has built the phase, or null before it has.
	 *
	 * Key presses arrive from the input thread and can arrive before the first world frame, which
	 * is the whole reason this never creates anything: the render loop owns that, and a key press
	 * that built the DLSS path would build it off the render thread.
	 */
	@JvmStatic
	fun activeControls(): DlssRuntimeControls? = controls

	/** The world phase, initializing it on the first call. Render loop only. */
	@JvmStatic
	fun worldPhase(): DlssWorldPhase? {
		if (initialized) {
			return phase
		}

		initialized = true
		val session = McDlss.session
		if (!session.config.enabled) {
			LOGGER.info("DLSS disabled by {}; every frame renders vanilla", DlssStartupConfig.ENABLED_PROPERTY)
			return null
		}

		phase = try {
			val native = DlssNative.open(DlssExtensionBootstrap.nativeLibrary())
			val diagnostics: (String) -> Unit = { message -> LOGGER.info(message) }
			val runtime = DlssRenderRuntime.forMinecraft(session, native, diagnostics)
			// The controls answer on the same sink the rest of the mod reports on as well as in
			// chat, so a session witnessed live and a session read back from the log agree.
			controls = DlssRuntimeControls(runtime) { message ->
				LOGGER.info(message)
				DlssChatReadout.send(message)
			}
			DlssWorldPhase.forMinecraft(runtime, diagnostics)
		} catch (error: Throwable) {
			LOGGER.warn("DLSS native bridge unavailable; every frame renders vanilla", error)
			session.latchFailure(DlssNativeFailure(DlssNativeStage.LOAD_LIBRARY, 0, error.toString()))
			null
		}
		return phase
	}
}
