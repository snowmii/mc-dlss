package me.snowmii.dlss

import com.mojang.blaze3d.pipeline.RenderTarget

/**
 * Production owner of everything the render loop needs from DLSS.
 *
 * Until this class existed, [DlssLifecycleAdapter], [WorldTargetRouter], and
 * [DlssSceneTarget] each had a correct contract and no caller. The runtime is the single
 * place that turns a captured Vulkan context into a READY session and then answers one
 * question per frame: *which target does the world phase render into?*
 *
 * Startup is attempted exactly once. NGX initialization needs a live Vulkan device, so it
 * cannot happen at mod-init time; the first frame that asks for a world target drives it.
 * A failed or skipped startup is never retried — the session latches vanilla fallback and
 * every later frame routes full-resolution, which is what the effort contract requires of
 * a failed native stage.
 *
 * Everything is constructor-injected so the whole lifecycle is verifiable off the render
 * thread; [forMinecraft] supplies the production wiring.
 */
class DlssRenderRuntime(
	private val session: DlssSession,
	private val sceneTarget: DlssSceneTarget,
	private val startup: () -> DlssDimensions?,
	private val clock: () -> Long = System::nanoTime,
	/**
	 * Records this frame's DLSS work, or null for a runtime that only routes targets. The world
	 * phase owns *when* it runs; the runtime owns it because it is scoped to the same session.
	 */
	val frameEvaluation: DlssFrameEvaluation? = null,
	/**
	 * Re-queries the native render dimensions for a mode and preset chosen while the session
	 * runs, or null for a runtime whose configuration cannot change. Separate from [startup]
	 * because it must not re-initialize NGX.
	 */
	private val reconfigure: ((DlssQualityMode, DlssRenderPreset) -> DlssDimensions?)? = null,
) : AutoCloseable {
	private var startupAttempted = false
	private var router: WorldTargetRouter? = null
	private var jitter: DlssJitter? = null
	private var motion: DlssCameraMotion? = null

	/** Quality mode this runtime is rendering at, which starts as the configured one. */
	var qualityMode: DlssQualityMode = session.config.qualityMode
		private set

	/** Preset this runtime is rendering with, which starts as the configured one. */
	var renderPreset: DlssRenderPreset = session.config.renderPreset
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
	var activeJitter: DlssJitterOffset? = null
		private set

	/**
	 * Camera-only motion for the current world phase, or null outside an eligible DLSS phase and
	 * for an eligible phase that was routed without a camera sample.
	 */
	var activeMotion: DlssFrameMotion? = null
		private set

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
		val activeRouter = if (runtimeEnabled) ensureStarted() else null
		if (activeRouter == null) {
			// No DLSS this session: release any target held from an earlier eligible frame.
			sceneTarget.close()
			activeRoute = null
			activeWorldTarget = null
			activeJitter = null
			activeMotion = null
			return null
		}

		val route = activeRouter.route(normalInWorldFrame, outputDimensions)
		val target = sceneTarget.acquire(route)
		activeRoute = route
		activeWorldTarget = target
		// A vanilla frame breaks the accumulated history, so it restarts the sequence rather
		// than consuming a phase no evaluation will ever see.
		val offset = if (target != null) {
			jitter?.advance()
		} else {
			jitter?.reset()
			null
		}
		activeJitter = offset
		activeMotion = if (offset != null && camera != null) {
			motion?.advance(camera, offset, clock())
		} else {
			motion?.reset()
			null
		}
		return target
	}

	/** Closes the world phase. The scene target stays allocated for reuse across frames. */
	fun endWorldPhase() {
		activeRoute = null
		activeWorldTarget = null
		activeJitter = null
		activeMotion = null
	}

	/**
	 * Forgets the camera the next frame would reproject against.
	 *
	 * A frame that decided its route but never finished rendering still moved the predecessor
	 * forward. Nothing accumulated it, so the frame after it must not measure motion from a
	 * camera no image was ever produced for.
	 */
	fun resetMotionHistory() {
		motion?.reset()
	}

	/**
	 * Forgets everything this scene accumulated: the camera the next frame would reproject against
	 * and the jitter phase it would continue.
	 *
	 * Used when the scene itself is replaced rather than when one frame was lost. A world load or a
	 * dimension change can leave the camera exactly where it stood while every surface in the frame
	 * becomes a different one, so nothing the frames themselves carry distinguishes it from standing
	 * still - and the accumulated history it would keep describes a world that is gone.
	 */
	fun resetHistory() {
		jitter?.reset()
		motion?.reset()
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
		releaseFrameState()
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
	fun applyConfiguration(mode: DlssQualityMode, preset: DlssRenderPreset): Boolean {
		if (mode == qualityMode && preset == renderPreset) {
			return false
		}

		// Nothing has started yet, so there is no native configuration to change - the first
		// frame will start against whatever is chosen here.
		if (router == null) {
			qualityMode = mode
			renderPreset = preset
			return true
		}

		val dimensions = reconfigure?.invoke(mode, preset) ?: return false
		qualityMode = mode
		renderPreset = preset
		renderDimensions = dimensions
		jitter = DlssJitter(dimensions, session.config.outputDimensions)
		motion = DlssCameraMotion(dimensions)
		router = WorldTargetRouter(session, dimensions)
		releaseFrameState()
		return true
	}

	override fun close() {
		endWorldPhase()
		// Before the session closes: releasing the native images needs a session still READY.
		frameEvaluation?.close()
		sceneTarget.close()
		router = null
		jitter = null
		motion = null
		renderDimensions = null
		session.close()
	}

	/**
	 * Drops everything sized from the configuration that just stopped applying.
	 *
	 * The scene target and the native images are released rather than resized: both are acquired
	 * from the configuration on the next eligible frame, and a frame evaluating into images sized
	 * for the previous configuration is the failure that silently latches full resolution.
	 */
	private fun releaseFrameState() {
		endWorldPhase()
		sceneTarget.close()
		frameEvaluation?.close()
		resetHistory()
	}

	/**
	 * Runs native startup at most once and returns the router, or null when DLSS is not
	 * available for this session.
	 */
	private fun ensureStarted(): WorldTargetRouter? {
		router?.let { return it }
		if (startupAttempted) {
			return null
		}

		startupAttempted = true
		val startupDimensions = startup() ?: return null
		if (session.state != DlssSessionState.READY) {
			return null
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

		renderDimensions = dimensions
		jitter = DlssJitter(dimensions, session.config.outputDimensions)
		motion = DlssCameraMotion(dimensions)
		return WorldTargetRouter(session, dimensions).also { router = it }
	}

	companion object {
		/**
		 * Production wiring: NGX startup against the captured Minecraft Vulkan context and
		 * a Minecraft-allocated scene target. Returns null when no Vulkan context has been
		 * captured yet or the configuration supplies no SDK/data path, because
		 * [DlssLifecycleAdapter.initialize] cannot run without either.
		 */
		@JvmStatic
		fun forMinecraft(
			session: DlssSession,
			native: DlssNativeApi,
			diagnostics: (String) -> Unit = {},
		): DlssRenderRuntime {
			val adapter = DlssLifecycleAdapter(session, native)
			return DlssRenderRuntime(session, DlssSceneTarget.forMinecraft(), frameEvaluation = DlssFrameEvaluation(
				adapter,
				{ VulkanContextRegistry.current },
				diagnostics,
			), reconfigure = adapter::reconfigure, startup = {
				val context = VulkanContextRegistry.current
				val sdkPath = session.config.sdkPath
				val dataPath = session.config.dataPath
				if (context == null || sdkPath == null || dataPath == null) {
					// Each of these silently disables DLSS for the whole session, so name the one
					// that is actually missing rather than leaving a vanilla-looking frame.
					diagnostics(
						"DLSS startup skipped:" +
							" vulkan-context=${if (context == null) "missing" else "captured"}" +
							" ${DlssStartupConfig.SDK_PATH_PROPERTY}=${sdkPath ?: "unset"}" +
							" ${DlssStartupConfig.DATA_PATH_PROPERTY}=${dataPath ?: "unset"}",
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
