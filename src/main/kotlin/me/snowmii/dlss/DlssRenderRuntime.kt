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
) : AutoCloseable {
	private var startupAttempted = false
	private var router: WorldTargetRouter? = null

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

	/**
	 * Opens the world phase. Returns the low-resolution scene target for an eligible DLSS
	 * frame, or null when the frame must use the vanilla main target.
	 */
	fun beginWorldPhase(normalInWorldFrame: Boolean, outputDimensions: DlssDimensions): RenderTarget? {
		val activeRouter = ensureStarted()
		if (activeRouter == null) {
			// No DLSS this session: release any target held from an earlier eligible frame.
			sceneTarget.close()
			activeRoute = null
			activeWorldTarget = null
			return null
		}

		val route = activeRouter.route(normalInWorldFrame, outputDimensions)
		val target = sceneTarget.acquire(route)
		activeRoute = route
		activeWorldTarget = target
		return target
	}

	/** Closes the world phase. The scene target stays allocated for reuse across frames. */
	fun endWorldPhase() {
		activeRoute = null
		activeWorldTarget = null
	}

	override fun close() {
		endWorldPhase()
		sceneTarget.close()
		router = null
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
		fun forMinecraft(session: DlssSession, native: DlssNativeApi): DlssRenderRuntime {
			val adapter = DlssLifecycleAdapter(session, native)
			return DlssRenderRuntime(session, DlssSceneTarget.forMinecraft()) {
				val context = VulkanContextRegistry.current
				val sdkPath = session.config.sdkPath
				val dataPath = session.config.dataPath
				if (context == null || sdkPath == null || dataPath == null) {
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
			}
		}
	}
}
