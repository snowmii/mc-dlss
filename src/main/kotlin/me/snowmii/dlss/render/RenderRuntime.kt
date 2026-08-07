package me.snowmii.dlss.render
import me.snowmii.dlss.session.DlssFrameDecision
import me.snowmii.dlss.session.DlssFrameRoute
import me.snowmii.dlss.bridge.NativeApi
import me.snowmii.dlss.bridge.VulkanContextRegistry
import me.snowmii.dlss.readout.SessionReadout
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.session.SRMode
import me.snowmii.dlss.session.SRModelPreset
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssSessionState
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.config.ModConfig
import me.snowmii.dlss.session.LifecycleAdapter
import com.mojang.blaze3d.pipeline.RenderTarget

/** Dimension policy consumed by renderer hooks before world target allocation. */
data class WorldTargetRoute(
	val frame: DlssFrameDecision,
	val worldDimensions: DlssDimensions,
	val mainTargetDimensions: DlssDimensions,
)

/**
 * Production owner of everything the render loop needs from DLSS.
 *
 * Until this class existed, [LifecycleAdapter] and [SceneTarget] each had a correct
 * contract and no caller. The runtime is the single
 * place that turns a captured Vulkan context into a READY session and then answers one
 * question per frame: *which target does the world phase render into?*
 *
 * Startup is attempted exactly once. NGX initialization needs a live Vulkan device, so it
 * cannot happen at mod-init time; the first frame that asks for a world target drives it.
 * A failed or skipped startup is never retried — the session latches vanilla fallback and
 * every later frame routes full-resolution, which is what the effort contract requires of
 * a failed native stage.
 *
 * What it does *not* own is deliberate. The GPU objects a configuration holds, and the device
 * stall that makes freeing them safe, belong to [FrameResources]; the jitter and motion
 * sequences a frame accumulates against its predecessor belong to [WorldPhaseState]. Both were
 * threaded through this class as loose fields and a release rule repeated at four call sites.
 * What is left here is the startup latch, the configuration in effect, and the routing decision.
 *
 * Everything is constructor-injected so the whole lifecycle is verifiable off the render
 * thread; [forMinecraft] supplies the production wiring.
 */
class RenderRuntime(
	private val session: DlssSession,
	sceneTarget: SceneTarget,
	private val startup: () -> DlssDimensions?,
	private val clock: () -> Long = System::nanoTime,
	/**
	 * Records this frame's DLSS work, or null for a runtime that only routes targets. The world
	 * phase owns *when* it runs; the runtime owns it because it is scoped to the same session.
	 */
	val frameEvaluation: FrameEvaluation? = null,
	/**
	 * Re-queries the native render dimensions for a mode and preset chosen while the session
	 * runs, or null for a runtime whose configuration cannot change. Separate from [startup]
	 * because it must not re-initialize NGX.
	 */
	private val reconfigure: ((SRMode, SRModelPreset) -> DlssDimensions?)? = null,
	/**
	 * Blocks until the device has finished every frame already submitted, or does nothing for a
	 * runtime with no device behind it.
	 *
	 * Releasing the scene target and the native images frees GPU objects that Minecraft's still
	 * in-flight frames read from. Nothing on the CPU side observes that: the key that triggered
	 * the release is polled between frames, the release itself succeeds, and the device is lost
	 * several frames later inside an unrelated semaphore wait.
	 */
	quiesce: () -> Unit = {},
) : AutoCloseable {
	private val resources = FrameResources(sceneTarget, frameEvaluation, quiesce)
	private val phase = WorldPhaseState()
	private var startupAttempted = false

	/** Quality mode this runtime is rendering at, which starts as the configured one. */
	var qualityMode: SRMode = session.config.qualityMode
		private set

	/** Preset this runtime is rendering with, which starts as the configured one. */
	var renderPreset: SRModelPreset = session.config.renderPreset
		private set

	/**
	 * Whether DLSS is switched on right now, independent of whether the session could ever use it.
	 *
	 * The configuration's own `enabled` decides whether the session has DLSS at all; this decides
	 * whether the reviewer currently wants it, which is the switch AC-2 and AC-5 are witnessed by.
	 */
	var runtimeEnabled: Boolean = true
		private set

	/**
	 * Target the world phase must render into, or null when the frame renders vanilla
	 * full-resolution into Minecraft's main target.
	 */
	@Volatile
	var activeWorldTarget: RenderTarget? = null
		private set

	/** Route chosen for the current world phase, or null outside one. */
	var activeRoute: WorldTargetRoute? = null
		private set

	/** NGX-queried render dimensions, or null until a successful startup. */
	var renderDimensions: DlssDimensions? = null
		private set

	/** Startup configuration this runtime's session resolved. */
	val config: DlssStartupConfig
		get() = session.config

	/** Session state as of now, which the acceptance record reports. */
	val sessionState: DlssSessionState
		get() = session.state

	/**
	 * Sub-pixel jitter for the current world phase, or null outside an eligible DLSS phase.
	 *
	 * The world projection and the NGX evaluation parameter both have to describe the same
	 * offset, so the phase advances the sequence exactly once and publishes the single value
	 * both of them read.
	 */
	val activeJitter: DlssJitterOffset?
		get() = phase.activeJitter

	/**
	 * Camera-only motion for the current world phase, or null outside an eligible DLSS phase and
	 * for an eligible phase that was routed without a camera sample.
	 */
	val activeMotion: DlssFrameMotion?
		get() = phase.activeMotion

	/**
	 * Opens the world phase. Returns the low-resolution scene target for an eligible DLSS
	 * frame, or null when the frame must use the vanilla main target.
	 *
	 * [camera] is this frame's camera as the world projection seam sampled it. A null sample
	 * still routes the frame; it publishes no motion and breaks the motion chain, because a
	 * frame whose camera was never observed cannot be reprojected against.
	 */
	fun beginWorldPhase(
		normalInWorldFrame: Boolean,
		outputDimensions: DlssDimensions,
		camera: DlssCameraSample? = null,
	): RenderTarget? {
		// Switched off takes effect before startup is ever attempted, so a session that begins
		// switched off never initializes NGX at all.
		val started = if (runtimeEnabled) ensureStarted() else false
		if (!started) {
			// No DLSS this session: release any target held from an earlier eligible frame. The
			// primitive also drops the published phase state and resets the sequences, which have
			// nothing to continue into a session that never started.
			releaseFrameState(releaseImages = false)
			return null
		}

		val route = routeFrame(normalInWorldFrame, outputDimensions)
		val target = resources.acquire(route)
		activeRoute = route
		activeWorldTarget = target
		phase.open(target, camera, clock())
		return target
	}

	/** Closes the world phase. The scene target stays allocated for reuse across frames. */
	fun endWorldPhase() {
		activeRoute = null
		activeWorldTarget = null
		phase.close()
	}

	/**
	 * Forgets the camera the next frame would reproject against.
	 *
	 * A frame that decided its route but never finished rendering still moved the predecessor
	 * forward. Nothing accumulated it, so the frame after it must not measure motion from a
	 * camera no image was ever produced for.
	 */
	fun resetMotionHistory() {
		phase.resetMotion()
	}

	/**
	 * Forgets everything this scene accumulated: the camera the next frame would reproject
	 * against and the jitter phase it would continue.
	 *
	 * Used when the scene itself is replaced rather than when one frame was lost.
	 */
	fun resetHistory() {
		phase.reset()
	}

	/**
	 * Switches DLSS on or off for the frames that follow, and reports whether anything changed.
	 *
	 * Switching off is the full-resolution path the contract already requires of a failure, minus
	 * the failure: the low-resolution target is released, the native images go with it, and the
	 * accumulated history is dropped, because the frames that come back are not continuous with
	 * the ones that stopped.
	 */
	fun setEnabled(enabled: Boolean): Boolean {
		if (enabled == runtimeEnabled) {
			return false
		}

		runtimeEnabled = enabled
		releaseFrameState(releaseImages = true)
		return true
	}

	/**
	 * Re-renders at [mode] with [preset] from the next frame on, and reports whether it took.
	 *
	 * A mode change is a different render size, so everything sized from that size is rebuilt: the
	 * jitter sequence whose length is the pixel ratio, the motion reprojection whose scale is the
	 * render dimensions, the router that decides the scene target's size, the scene target itself,
	 * and the native images the evaluation writes into. A preset change rebuilds none of that and
	 * still goes through here, because the native feature is recreated on either one.
	 *
	 * A refused reconfiguration leaves the runtime on the configuration it was already running:
	 * the native side rejected it, so nothing about the frames that follow has changed, and the
	 * caller reports the mode that is still in effect rather than the one that was asked for.
	 */
	fun applyConfiguration(mode: SRMode, preset: SRModelPreset): Boolean {
		if (mode == qualityMode && preset == renderPreset) {
			return false
		}

		// Nothing has started yet, so there is no native configuration to change - the first
		// frame will start against whatever is chosen here.
		if (renderDimensions == null) {
			qualityMode = mode
			renderPreset = preset
			return true
		}

		val dimensions = reconfigure?.invoke(mode, preset) ?: return false
		qualityMode = mode
		renderPreset = preset
		rebuildFrom(dimensions)
		releaseFrameState(releaseImages = true)
		return true
	}

	override fun close() {
		// Unconditional teardown wait first, then the guarded release: see FrameResources.close.
		endWorldPhase()
		resources.close()
		phase.reset()
		// Before the session closes: releasing the native images needs a session still READY.
		phase.discard()
		renderDimensions = null
		session.close()
	}

	/**
	 * Ends the phase and hands the held GPU objects back, then restarts the sequences that
	 * described the frames those objects belonged to.
	 *
	 * The stall and the guard on it live in [FrameResources]; what this adds is that the
	 * accumulated history goes with the resources, because the frames that come back are not
	 * continuous with the ones that stopped.
	 */
	private fun releaseFrameState(releaseImages: Boolean) {
		endWorldPhase()
		resources.release(releaseImages)
		phase.reset()
	}

	/**
	 * Runs native startup at most once and returns whether DLSS is available for this session.
	 */
	private fun ensureStarted(): Boolean {
		if (renderDimensions != null) {
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

		// Startup configures NGX from the session's own configuration, so a mode or preset chosen
		// before the first frame - which is the only time a change is free - has to be re-applied
		// once there is a native side to apply it to.
		val dimensions = if (qualityMode == session.config.qualityMode && renderPreset == session.config.renderPreset) {
			startupDimensions
		} else {
			reconfigure?.invoke(qualityMode, renderPreset) ?: run {
				qualityMode = session.config.qualityMode
				renderPreset = session.config.renderPreset
				startupDimensions
			}
		}

		rebuildFrom(dimensions)
		return true
	}

	/**
	 * Rebuilds everything sized from the render dimensions after they change: the two sequences
	 * the phase accumulates, and the render dimensions themselves, which are the routing
	 * decision's one source of truth.
	 */
	private fun rebuildFrom(dimensions: DlssDimensions) {
		renderDimensions = dimensions
		phase.rebuild(dimensions, session.config.outputDimensions)
	}

	/**
	 * Decides this frame's world target size from the session's route.
	 *
	 * An eligible DLSS frame renders at the NGX-queried render dimensions; every vanilla frame
	 * renders at the output size. The render dimensions are this runtime's own field, so there is
	 * exactly one copy of them for the whole route path.
	 */
	private fun routeFrame(normalInWorldFrame: Boolean, outputDimensions: DlssDimensions): WorldTargetRoute {
		val frame = session.beginFrame(normalInWorldFrame, outputDimensions)
		// A DLSS route is only possible once startup set the render dimensions; a route that is
		// somehow DLSS without them degrades to the output size rather than a null target.
		val worldDimensions = if (frame.route == DlssFrameRoute.DLSS) {
			renderDimensions ?: outputDimensions
		} else {
			outputDimensions
		}
		return WorldTargetRoute(
			frame = frame,
			worldDimensions = worldDimensions,
			mainTargetDimensions = outputDimensions,
		)
	}

	companion object {
		/**
		 * Production wiring: NGX startup against the captured Minecraft Vulkan context and
		 * a Minecraft-allocated scene target. Returns null when no Vulkan context has been
		 * captured yet or the configuration supplies no SDK/data path, because
		 * [LifecycleAdapter.initialize] cannot run without either.
		 */
		fun forMinecraft(
			session: DlssSession,
			native: NativeApi,
			diagnostics: (String) -> Unit = {},
			/**
			 * The session readout the evaluation feeds its first record to, or null for a runtime
			 * with no reporting seam.
			 */
			readout: SessionReadout? = null,
		): RenderRuntime {
			val adapter = LifecycleAdapter(session, native)
			return RenderRuntime(session, SceneTarget.forMinecraft(), frameEvaluation = FrameEvaluation(
				adapter,
				{ VulkanContextRegistry.getCurrent() },
				readout,
			), reconfigure = adapter::reconfigure, quiesce = { adapter.waitDeviceIdle() }, startup = {
				val context = VulkanContextRegistry.getCurrent()
				val sdkPath = session.config.sdkPath
				val dataPath = session.config.dataPath
				if (context == null || sdkPath == null || dataPath == null) {
					// Each of these silently disables DLSS for the whole session, so name the one
					// that is actually missing rather than leaving a vanilla-looking frame.
					diagnostics(
						"DLSS startup skipped:" +
							" vulkan-context=${if (context == null) "missing" else "captured"}" +
							" ${ModConfig.SDK_PATH_PROPERTY}=${sdkPath ?: "unset"}" +
							" ${ModConfig.DATA_PATH_PROPERTY}=${dataPath ?: "unset"}",
					)
					null
				} else {
					adapter.initialize(
						vkInstance = context.instanceHandle,
						vkPhysicalDevice = context.physicalDeviceHandle,
						vkDevice = context.deviceHandle,
						sdkPath = sdkPath,
						dataPath = dataPath,
					)
				}
			})
		}
	}
}
