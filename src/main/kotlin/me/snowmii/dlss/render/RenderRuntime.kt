package me.snowmii.dlss.render
import me.snowmii.dlss.DlssFrameDecision
import me.snowmii.dlss.DlssFrameRoute
import me.snowmii.streamline.StreamlineSession
import me.snowmii.dlss.readout.FramePacingProbe
import me.snowmii.dlss.vk.VulkanContextRegistry
import me.snowmii.dlss.readout.SessionReadout
import me.snowmii.streamline.Dimensions
import me.snowmii.dlss.ReflexMode
import me.snowmii.dlss.SRMode
import me.snowmii.dlss.SRModelPreset
import me.snowmii.dlss.DlssSession
import me.snowmii.dlss.DlssSessionState
import me.snowmii.dlss.DlssStartupConfig
import me.snowmii.dlss.client.ModConfig
import net.minecraft.client.renderer.entity.state.EntityRenderState
import org.joml.Matrix4f
import java.util.IdentityHashMap
import me.snowmii.dlss.streamline.LifecycleAdapter
import me.snowmii.dlss.streamline.SessionBridge
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.LoadingOverlay
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.renderpearl.api.textures.GpuTextureView

data class WorldTargetRoute(
	val frame: DlssFrameDecision,
	val worldDimensions: Dimensions,
	val mainTargetDimensions: Dimensions,
)

class RenderRuntime(
	private val session: DlssSession,
	sceneTarget: SceneTarget,
	private val startup: () -> Dimensions?,
	private val clock: () -> Long = System::nanoTime,
	val frameEvaluation: FrameEvaluation? = null,
	private val bridge: SessionBridge? = null,
	val frameGeneration: FgSurfacePolicy = FgSurfacePolicy(),
	private val invalidateSurfaceConfiguration: () -> Unit = {},
	private val fgFrameSupported: (Boolean, Dimensions) -> Boolean =
		{ normalInWorldFrame, outputDimensions ->
			normalInWorldFrame && outputDimensions == session.outputDimensions
		},
	private val diagnostics: (String) -> Unit = {},
) : AutoCloseable {
	private val frameResources = FrameResources(sceneTarget, frameEvaluation) { bridge?.waitDeviceIdle() }
	private val worldPhaseState = WorldPhaseState()
	private var currentWorldViewProjection: Matrix4f? = null
	private var startupAttempted = false

	private var pendingOutputDimensions: Dimensions? = null

	private var outputDimensionsSeen = false

	val outputDimensions: Dimensions
		get() = session.outputDimensions

	var qualityMode: SRMode = session.config.qualityMode
		private set

	var renderPreset: SRModelPreset = session.config.renderPreset
		private set

	var dlssEnabled: Boolean = true
		private set

	var reflexMode: ReflexMode = ModConfig.user.reflexMode
		private set

	fun setReflexMode(mode: ReflexMode): Boolean {
		if (session.state == DlssSessionState.READY && bridge?.setReflexMode(mode) == false) {
			return false
		}
		reflexMode = mode
		ModConfig.user.reflexMode = mode
		diagnostics("DLSS Reflex mode: ${mode.propertyValue}")
		return true
	}

	fun queryReflexState(): StreamlineSession.ReflexStateInfo? =
		bridge?.queryReflexState()

	val fgMultiplier: Int
		get() = frameGeneration.numFramesToGenerate

	val pacing = FramePacingProbe()

	@Volatile
	var worldRenderTarget: RenderTarget? = null
		private set

	var worldTargetRoute: WorldTargetRoute? = null
		private set

	var dlssRenderDimensions: Dimensions? = null
		private set

	val config: DlssStartupConfig
		get() = session.config

	val sessionState: DlssSessionState
		get() = session.state

	val activeJitter: DlssJitterOffset?
		get() = worldPhaseState.activeJitter

	val currentViewProjection: Matrix4f?
		get() = currentWorldViewProjection

	val activeMotion: DlssFrameMotion?
		get() = worldPhaseState.activeMotion

	private var fgStatusHealthy = true

	init {
		frameGeneration.recordFrameGenerationOff = { bridge?.recordFrameGenerationOff() }
	}

	fun beginWorldPhase(
		normalInWorldFrame: Boolean,
		outputDimensions: Dimensions,
		camera: DlssCameraSample? = null,
	): RenderTarget? {
		val started = if (dlssEnabled && !me.snowmii.dlss.api.RayReconstruction.referenceMode) startDlssOnce() else false

		if (started) {
			adoptOutputDimensions(outputDimensions)
		}

		if (frameGeneration.userEnabled) {
			pacing.begin(FramePacingProbe.Span.FG_STATUS_POLL)
			updateFrameGenerationHealth()
			pacing.end(FramePacingProbe.Span.FG_STATUS_POLL)
		}

		val frameSupported =
			started && fgStatusHealthy && fgFrameSupported(normalInWorldFrame, outputDimensions)
		frameGeneration.setCompositionSupported(frameSupported)

		if (!started) {
			releaseFrameState(releaseImages = false)
			return null
		}

		val route = chooseWorldTargetRoute(normalInWorldFrame, outputDimensions)
		val target = frameResources.acquire(route)
		worldTargetRoute = route
		worldRenderTarget = target
		currentWorldViewProjection = if (target != null && camera != null) {
			Matrix4f(camera.projection).mul(camera.viewRotation)
		} else {
			null
		}
		worldPhaseState.open(target, camera, clock())
		return target
	}

	fun endWorldPhase() {
		worldTargetRoute = null
		worldRenderTarget = null
		worldPhaseState.finish()
		currentWorldViewProjection = null
	}

	fun resetMotionHistory() {
		worldPhaseState.resetMotion()
		currentWorldViewProjection = null
	}

	fun resetHistory() {
		worldPhaseState.reset()
		currentWorldViewProjection = null
	}

	fun setDlssEnabled(enabled: Boolean): Boolean {
		if (enabled == dlssEnabled) {
			return false
		}

		dlssEnabled = enabled
		releaseFrameState(releaseImages = true)
		return true
	}

	fun setFrameGenerationEnabled(enabled: Boolean): Boolean =
		frameGeneration.setFrameGenerationActive(enabled)

	fun setFgMultiplier(numFramesToGenerate: Int): Boolean {
		if (numFramesToGenerate == fgMultiplier || numFramesToGenerate < 1) {
			return false
		}
		if (bridge?.setFgMultiplier(numFramesToGenerate) != true) {
			return false
		}
		frameGeneration.numFramesToGenerate = numFramesToGenerate
		invalidateSurfaceConfiguration()
		return true
	}

	fun cycleFgMultiplier(): Boolean {
		val state = bridge?.queryFgMultiplier() ?: return false
		val next = if (state.max <= 1 || state.current >= state.max) 1 else state.current + 1
		return setFgMultiplier(next)
	}

	fun applyConfiguration(mode: SRMode, preset: SRModelPreset): Boolean {
		if (mode == qualityMode && preset == renderPreset) {
			return false
		}

		if (dlssRenderDimensions == null) {
			qualityMode = mode
			renderPreset = preset
			return true
		}

		val dimensions = bridge?.reconfigure(mode, preset) ?: return false
		qualityMode = mode
		renderPreset = preset
		rebuildForRenderDimensions(dimensions)
		releaseFrameState(releaseImages = true)
		return true
	}

	override fun close() {
		endWorldPhase()
		frameResources.close()
		worldPhaseState.reset()
		worldPhaseState.discard()
		dlssRenderDimensions = null
		currentWorldViewProjection = null
		session.close()
	}

	private fun releaseFrameState(releaseImages: Boolean) {
		if (releaseImages) {
			frameGeneration.setCompositionSupported(false)
		}
		endWorldPhase()
		frameResources.release(releaseImages)
		worldPhaseState.reset()
	}

	private fun startDlssOnce(): Boolean {
		if (dlssRenderDimensions != null) {
			return true
		}
		if (startupAttempted) {
			return false
		}

		startupAttempted = true
		val startupDimensions = startup() ?: return false
		if (session.state != DlssSessionState.READY) {
			return false
		}

		val dimensions = if (qualityMode == session.config.qualityMode && renderPreset == session.config.renderPreset) {
			startupDimensions
		} else {
			bridge?.reconfigure(qualityMode, renderPreset) ?: run {
				qualityMode = session.config.qualityMode
				renderPreset = session.config.renderPreset
				startupDimensions
			}
		}

		rebuildForRenderDimensions(dimensions)
		bridge?.setReflexMode(reflexMode)
		return true
	}

	private fun adoptOutputDimensions(dimensions: Dimensions): Boolean {
		if (session.config.outputPinned || dimensions == session.outputDimensions) {
			pendingOutputDimensions = null
			return false
		}

		val settled = !outputDimensionsSeen || pendingOutputDimensions == dimensions
		outputDimensionsSeen = true
		pendingOutputDimensions = dimensions
		if (!settled) {
			return false
		}

		val previous = session.outputDimensions
		if (!session.adoptOutputDimensions(dimensions)) {
			return false
		}
		pendingOutputDimensions = null

		val reconfigured = bridge?.reconfigure(qualityMode, renderPreset)
		if (reconfigured == null) {
			session.adoptOutputDimensions(previous)
			return false
		}

		rebuildForRenderDimensions(reconfigured)
		releaseFrameState(releaseImages = true)
		diagnostics("DLSS output resolution: $previous -> $dimensions render=$reconfigured")
		return true
	}

	private fun rebuildForRenderDimensions(dimensions: Dimensions) {
		dlssRenderDimensions = dimensions
		worldPhaseState.rebuild(dimensions, session.outputDimensions)
	}

	private fun chooseWorldTargetRoute(normalInWorldFrame: Boolean, outputDimensions: Dimensions): WorldTargetRoute {
		val frame = session.beginFrame(normalInWorldFrame, outputDimensions)
		val worldDimensions = if (frame.route == DlssFrameRoute.DLSS) {
			dlssRenderDimensions ?: outputDimensions
		} else {
			outputDimensions
		}
		return WorldTargetRoute(
			frame = frame,
			worldDimensions = worldDimensions,
			mainTargetDimensions = outputDimensions,
		)
	}

	private fun updateFrameGenerationHealth() {
		val state = bridge?.queryFgState() ?: return
		val healthy = state.status == FG_STATUS_OK
		if (healthy == fgStatusHealthy) {
			return
		}
		fgStatusHealthy = healthy
		diagnostics(
			if (healthy) {
				"Frame generation resumed: slDLSSGGetState status=0x0 (eDLSSGStatusOk)."
			} else {
				"Frame generation suspended: slDLSSGGetState status=0x" +
					state.status.toString(16) +
					" (eDLSSGStatusOk=0); composition suspended and the eOff options retained while " +
					"the status is unhealthy, swapchain policy unchanged, and the first healthy " +
					"frame resumes."
			},
		)
	}

	companion object {
		private const val FG_STATUS_OK = 0

		fun isFgFrameSupported(
			normalInWorldFrame: Boolean,
			outputMatches: Boolean,
			paused: Boolean,
			loading: Boolean,
			screenOpen: Boolean,
			fullscreenTransition: Boolean,
		): Boolean = normalInWorldFrame && outputMatches && !paused && !loading && !screenOpen && !fullscreenTransition

		fun forMinecraft(
			session: DlssSession,
			native: StreamlineSession,
			diagnostics: (String) -> Unit = {},
			readout: SessionReadout? = null,
		): RenderRuntime {
			val adapter = LifecycleAdapter(session, native)
			var lastFullscreen: Boolean? = null
			val frameGeneration = FgSurfacePolicy(
				invalidateSurfaceConfiguration = { Minecraft.getInstance().invalidateSurfaceConfiguration() },
			)
			return RenderRuntime(session, SceneTarget.forMinecraft(), frameEvaluation = FrameEvaluation(
				adapter,
				{ VulkanContextRegistry.getCurrent() },
				readout,
				frameGeneration,
				fgInputs = { WorldPhase.resolveFrameGenerationInputs() },
			),
			bridge = adapter,
			invalidateSurfaceConfiguration = { Minecraft.getInstance().invalidateSurfaceConfiguration() },
			diagnostics = diagnostics,
			fgFrameSupported = { normalInWorldFrame, outputDimensions ->
				val client = Minecraft.getInstance()
				val fullscreen = client.options.fullscreen().get()
				val transition = lastFullscreen != null && lastFullscreen != fullscreen
				lastFullscreen = fullscreen
				isFgFrameSupported(
					normalInWorldFrame = normalInWorldFrame,
					outputMatches = outputDimensions == session.outputDimensions,
					paused = client.isPaused,
					loading = client.gui.overlay() is LoadingOverlay,
					screenOpen = client.gui.screen() != null,
					fullscreenTransition = transition,
				)
			},
			frameGeneration = frameGeneration, startup = {
				val context = VulkanContextRegistry.getCurrent()
				if (context == null) {
					diagnostics("DLSS startup skipped: vulkan-context=missing")
					null
				} else {
					val gameDir = FabricLoader.getInstance().gameDir
					adapter.initialize(
						vkInstance = context.instanceHandle,
						vkPhysicalDevice = context.physicalDeviceHandle,
						vkDevice = context.deviceHandle,
						sdkPath = session.config.sdkPath ?: gameDir,
						dataPath = session.config.dataPath ?: gameDir,
					)
				}
			})
		}
	}
}
