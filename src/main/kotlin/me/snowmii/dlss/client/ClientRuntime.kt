package me.snowmii.dlss.client
import com.mojang.blaze3d.pipeline.RenderTarget
import me.snowmii.streamline.Streamline
import me.snowmii.streamline.StreamlineSession
import me.snowmii.dlss.readout.SessionReadout
import me.snowmii.dlss.render.RenderRuntime
import me.snowmii.dlss.render.WorldPhase
import me.snowmii.dlss.DlssNativeFailure
import me.snowmii.dlss.DlssNativeStage
import me.snowmii.dlss.render.ui.UiPhase
import me.snowmii.dlss.ModEntry
import net.minecraft.client.Minecraft
import org.lwjgl.sdl.SDLProperties
import org.lwjgl.sdl.SDLVideo
import org.lwjgl.system.Platform
import org.slf4j.LoggerFactory

interface RenderLoopView {
	fun worldPhase(): WorldPhase?

	fun uiPhase(): UiPhase?
}

interface ActiveView {
	fun activeWorldPhase(): WorldPhase?

	fun activeUiPhase(): UiPhase?

	fun activeControls(): RuntimeControls?

	fun isFgVsyncSupported(): Boolean
}

object ClientRuntime : RenderLoopView, ActiveView {
	private val LOGGER = LoggerFactory.getLogger(ModEntry.MOD_ID)

	@Volatile
	private var worldPhaseInstance: WorldPhase? = null

	@Volatile
	private var uiPhaseInstance: UiPhase? = null

	@Volatile
	private var runtimeControls: RuntimeControls? = null

	@Volatile
	private var nativeBridge: StreamlineSession? = null
	internal var sessionOpener: () -> StreamlineSession? = Streamline::open
	private var initializationAttempted = false

	@JvmStatic
	fun renderLoop(): RenderLoopView = this

	@JvmStatic
	fun active(): ActiveView = this

	override fun activeWorldPhase(): WorldPhase? = worldPhaseInstance

	override fun activeUiPhase(): UiPhase? = uiPhaseInstance

	override fun activeControls(): RuntimeControls? = runtimeControls

	override fun isFgVsyncSupported(): Boolean = nativeBridge?.isFgVsyncSupported() ?: false

	@JvmStatic
	fun resolveActiveTarget(worldOverride: RenderTarget?, uiOverride: RenderTarget?): RenderTarget? =
		worldOverride ?: uiOverride

	override fun uiPhase(): UiPhase? {
		if (!initializationAttempted) {
			worldPhase()
		}
		return uiPhaseInstance
	}

	override fun worldPhase(): WorldPhase? {
		if (initializationAttempted) {
			return worldPhaseInstance
		}

		initializationAttempted = true
		val session = ModEntry.session
		if (!session.config.enabled) {
			LOGGER.info("DLSS disabled by {}; every frame renders vanilla", ModConfig.ENABLED_PROPERTY)
			return null
		}

		worldPhaseInstance = try {
			val native = sessionOpener() ?: return null
			this.nativeBridge = native
			if (Platform.get() == Platform.WINDOWS) {
				val window = Minecraft.getInstance().window.handle()
				val props = SDLVideo.SDL_GetWindowProperties(window)
				val hwnd = SDLProperties.SDL_GetPointerProperty(
					props,
					SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER,
					0L,
				)
				if (hwnd != 0L && native.installPclWindow(hwnd) != StreamlineSession.SUCCESS_RESULT) {
					LOGGER.warn("PCL latency-stat window hook failed; NVIDIA latency overlay will be unavailable")
				}
			}
			val diagnostics = SessionReadout::emit
			val readout = SessionReadout.forMinecraft(diagnostics)
			val runtime = RenderRuntime.forMinecraft(session, native, diagnostics, readout)
			runtimeControls = RuntimeControls(runtime, diagnostics).also { controls ->
				if (!ModConfig.user.enabled) controls.setEnabled(false)
				if (ModConfig.user.frameGeneration) controls.setFrameGenerationEnabled(true)
				controls.setRrModelPreset(ModConfig.user.rrModelPreset)
				if (ModConfig.user.rayReconstruction) controls.setRayReconstructionEnabled(true)
				controls.setReflexMode(ModConfig.user.reflexMode)
			}
			WorldPhase.forMinecraft(runtime, readout)
		} catch (error: Throwable) {
			LOGGER.warn("DLSS native bridge unavailable; every frame renders vanilla", error)
			session.latchFailure(DlssNativeFailure(DlssNativeStage.LOAD_LIBRARY, 0, error.toString()))
			null
		}
		uiPhaseInstance = if (worldPhaseInstance != null) UiPhase.forMinecraft() else null
		return worldPhaseInstance
	}

	@JvmStatic
	fun shutdown() {
		val ui = uiPhaseInstance
		val world = worldPhaseInstance
		val bridge = nativeBridge
		uiPhaseInstance = null
		worldPhaseInstance = null
		runtimeControls = null
		nativeBridge = null
		initializationAttempted = true

		close("UI phase", ui)
		close("world phase", world)
		close("native bridge", bridge)
		close("Streamline bootstrap", AutoCloseable { Streamline.shutdown() })
	}

	private fun close(what: String, resource: AutoCloseable?) {
		if (resource == null) {
			return
		}
		runCatching { resource.close() }
			.onFailure { error -> LOGGER.warn("DLSS shutdown: closing the {} failed", what, error) }
	}

}
