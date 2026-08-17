package me.snowmii.dlss.client
import com.mojang.blaze3d.pipeline.RenderTarget
import me.snowmii.streamline.Streamline
import me.snowmii.streamline.StreamlineSession
import me.snowmii.dlss.readout.SessionReadout
import me.snowmii.dlss.render.RenderRuntime
import me.snowmii.dlss.render.WorldPhase
import me.snowmii.dlss.session.DlssNativeFailure
import me.snowmii.dlss.session.DlssNativeStage
import me.snowmii.dlss.ui.UiPhase
import me.snowmii.dlss.config.ModConfig
import me.snowmii.McDlss
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window
import org.lwjgl.system.Platform
import org.slf4j.LoggerFactory

/**
 * The side of the client runtime only the render loop may use: the only place the DLSS path is
 * built. Every other seam holds [ActiveView], whose interface has no building method, so
 * a read-only call site cannot compile a creating call.
 */
interface RenderLoopView {
	/**
	 * The world phase, initializing it on the first call. Render loop only.
	 *
	 * Initialization touches the native library and Minecraft's renderer, so it must never run
	 * off the render thread - which is the whole reason this method exists only on the
	 * render-loop view.
	 */
	fun worldPhase(): WorldPhase?

	/**
	 * The UI phase, building the render loop's DLSS path on the first call. Render loop only,
	 * for the same reason as [worldPhase]: the first call acquires GPU resources.
	 */
	fun uiPhase(): UiPhase?
}

/**
 * The read-only side of the client runtime every other seam uses. Never creates anything: a key
 * press, a level change, or a target redirect must not build the DLSS path off the render thread.
 */
interface ActiveView {
	/** The world phase once it exists, without ever creating it. */
	fun activeWorldPhase(): WorldPhase?

	/** The UI phase once the render loop has built it, without ever creating it. */
	fun activeUiPhase(): UiPhase?

	/** The reviewer's controls once the render loop has built the phase, or null before it has. */
	fun activeControls(): RuntimeControls?
}

/**
 * Client-side holder for the world and UI phases the render-loop mixins share, behind two typed
 * views: [renderLoop] for the seams that may build the DLSS path, [active] for every other seam.
 *
 * The mixins cannot own the phases: `GameRenderer.mainRenderTarget()` is called from everywhere
 * and must stay allocation-free and side-effect-free, while `LevelRenderer.render` is the only
 * place allowed to start the DLSS path. So both phases initialize at most once, from the
 * render loop, and [ActiveView.activeWorldPhase] and [ActiveView.activeUiPhase] are the cheap
 * reads every other seam uses.
 *
 * Native library loading happens here rather than at mod init because a failure has to degrade
 * to vanilla instead of killing the client: it is latched on the session and never retried,
 * exactly like a failed native stage.
 */
object ClientRuntime : RenderLoopView, ActiveView {
	private val LOGGER = LoggerFactory.getLogger(McDlss.MOD_ID)

	@Volatile
	private var worldPhaseInstance: WorldPhase? = null

	@Volatile
	private var uiPhaseInstance: UiPhase? = null

	@Volatile
	private var runtimeControls: RuntimeControls? = null

	/**
	 * The bridge the render loop opened, kept so shutdown can close it. Nothing else holds it:
	 * the runtime only ever sees the [StreamlineSession] view.
	 */
	@Volatile
	private var nativeBridge: StreamlineSession? = null
	private var initializationAttempted = false

	/** The render-loop view: the only side that can build the DLSS path. */
	@JvmStatic
	fun renderLoop(): RenderLoopView = this

	/** The read-only view every other seam uses. */
	@JvmStatic
	fun active(): ActiveView = this

	override fun activeWorldPhase(): WorldPhase? = worldPhaseInstance

	override fun activeUiPhase(): UiPhase? = uiPhaseInstance

	override fun activeControls(): RuntimeControls? = runtimeControls

	/**
	 * Resolves what `GameRenderer.mainRenderTarget()` must answer: the world phase's scene target
	 * while that phase is open wins over the GUI window's UI target, and outside both windows the
	 * caller gets the vanilla main target. Kept here rather than in the mixin so the shared getter
	 * seam's precedence is verifiable off the render thread.
	 */
	@JvmStatic
	fun resolveActiveTarget(worldOverride: RenderTarget?, uiOverride: RenderTarget?): RenderTarget? =
		worldOverride ?: uiOverride

	override fun uiPhase(): UiPhase? {
		if (!initializationAttempted) {
			// The world phase is the single initializer: the UI phase shares its session gating
			// and native startup, and a frame's world phase always runs before its hand and GUI
			// windows.
			worldPhase()
		}
		return uiPhaseInstance
	}

	override fun worldPhase(): WorldPhase? {
		if (initializationAttempted) {
			return worldPhaseInstance
		}

		initializationAttempted = true
		val session = McDlss.session
		if (!session.config.enabled) {
			LOGGER.info("DLSS disabled by {}; every frame renders vanilla", ModConfig.ENABLED_PROPERTY)
			return null
		}

		worldPhaseInstance = try {
			val native = Streamline.open()
			this.nativeBridge = native
			if (Platform.get() == Platform.WINDOWS) {
				val hwnd = glfwGetWin32Window(Minecraft.getInstance().window.handle())
				if (native.installPclWindow(hwnd) != StreamlineSession.SUCCESS_RESULT) {
					LOGGER.warn("PCL latency-stat window hook failed; NVIDIA latency overlay will be unavailable")
				}
			}
			val diagnostics: (String) -> Unit = { message -> LOGGER.info(message) }
			// One reporter for the session: both the phase and evaluation feed their lines through
			// the readout created at this composition root.
			val readout = SessionReadout.forMinecraft(diagnostics)
			val runtime = RenderRuntime.forMinecraft(session, native, diagnostics, readout)
			// The controls answer on the same sink the rest of the mod reports on as well as in
			// chat, so a session witnessed live and a session read back from the log agree.
			runtimeControls = RuntimeControls(runtime) { message ->
				LOGGER.info(message)
				ChatReadout.send(message)
			}
			WorldPhase.forMinecraft(runtime, readout)
		} catch (error: Throwable) {
			LOGGER.warn("DLSS native bridge unavailable; every frame renders vanilla", error)
			session.latchFailure(DlssNativeFailure(DlssNativeStage.LOAD_LIBRARY, 0, error.toString()))
			null
		}
		// The UI phase rides the world phase's startup: an active mod owns both windows, and a
		// mod that never built the world path routes no GUI either.
		uiPhaseInstance = if (worldPhaseInstance != null) UiPhase.forMinecraft() else null
		return worldPhaseInstance
	}

	/**
	 * Releases everything the DLSS path owns, while the device that owns it is still alive.
	 *
	 * `Minecraft.close()` destroys the swapchain and then the Vulkan device. Streamline's
	 * DLSS-G still holds the tagged resources and its present hook at that point, and tearing
	 * the device down underneath a live feature faults inside NVIDIA's own libraries - after
	 * "Stopping!" is logged, so the game log shows a clean exit and the crash lands on the
	 * process. Closing here puts the release in front of the destroy.
	 *
	 * Nothing may propagate: a failed release must not turn an exiting client into a
	 * "Shutdown failure!". Each step is closed independently so one failure cannot skip the
	 * rest, and every phase is dropped either way - a second call, and every read through
	 * [ActiveView] on the way out, has to answer "no DLSS path".
	 */
	@JvmStatic
	fun shutdown() {
		val ui = uiPhaseInstance
		val world = worldPhaseInstance
		val bridge = nativeBridge
		uiPhaseInstance = null
		worldPhaseInstance = null
		runtimeControls = null
		nativeBridge = null
		// Left latched: the client is exiting, and a rebuild from a late render call would open
		// the native bridge again against a device that is already going away.
		initializationAttempted = true

		close("UI phase", ui)
		close("world phase", world)
		// Last: the world phase releases native images through this bridge on its way down.
		close("native bridge", bridge)
	}

	private fun close(what: String, resource: AutoCloseable?) {
		if (resource == null) {
			return
		}
		runCatching { resource.close() }
			.onFailure { error -> LOGGER.warn("DLSS shutdown: closing the {} failed", what, error) }
	}

	/** Whether the render loop has built the DLSS path, used by the seam's own test. */
	internal val isInitialized: Boolean
		get() = initializationAttempted
}
