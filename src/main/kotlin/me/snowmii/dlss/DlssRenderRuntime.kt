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
) : AutoCloseable {
	private var startupAttempted = false
	private var router: WorldTargetRouter? = null
	private var jitter: DlssJitter? = null
	private var motion: DlssCameraMotion? = null

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
		val activeRouter = ensureStarted()
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
	 * Runs native startup at most once and returns the router, or null when DLSS is not
	 * available for this session.
	 */
	private fun ensureStarted(): WorldTargetRouter? {
		router?.let { return it }
		if (startupAttempted) {
			return null
		}

		startupAttempted = true
		val dimensions = startup() ?: return null
		if (session.state != DlssSessionState.READY) {
			return null
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
			), startup = {
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
