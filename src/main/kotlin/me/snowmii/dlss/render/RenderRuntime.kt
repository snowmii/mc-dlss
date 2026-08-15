package me.snowmii.dlss.render
import me.snowmii.dlss.session.DlssFrameDecision
import me.snowmii.dlss.session.DlssFrameRoute
import me.snowmii.dlss.bridge.NativeApi
import me.snowmii.dlss.bridge.FgState
import me.snowmii.dlss.bridge.VulkanContextRegistry
import me.snowmii.dlss.readout.SessionReadout
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.session.SRMode
import me.snowmii.dlss.session.SRModelPreset
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssSessionState
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.config.ModConfig
import me.snowmii.dlss.mrt.MotionVectorCompatibility
import me.snowmii.dlss.mrt.MotionVectorPipeline
import me.snowmii.dlss.mrt.MotionVectorRoute
import me.snowmii.dlss.mrt.ObjectMotionState
import net.minecraft.client.renderer.entity.state.EntityRenderState
import org.joml.Matrix4f
import java.util.IdentityHashMap
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.fg.FgSurfacePolicy
import net.minecraft.client.Minecraft
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.textures.GpuTextureView

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
 * Startup is attempted exactly once. Streamline-backed startup needs a live Vulkan device, so it
 * cannot happen at mod-init time; the first frame that asks for a world target drives it.
 * A failed or skipped startup is never retried — the session latches vanilla fallback and
 * every later frame routes full-resolution, which is what the effort contract requires of
 * a failed native stage.
 *
 * What it does *not* own is deliberate. The GPU objects a configuration holds, and the device
 * stall that makes freeing them safe, belong to [FrameResources]; the jitter, motion, and
 * object-pose sequences a frame accumulates against its predecessor belong to
 * [WorldPhaseState]. Both were
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
	 * because it must not re-initialize the Streamline session.
	 */
	private val reconfigure: ((SRMode, SRModelPreset) -> DlssDimensions?)? = null,
	/** Session compatibility latch populated by world-pipeline compilation. */
	private val motionVectors: MotionVectorCompatibility = MotionVectorCompatibility(),
	/**
	 * The FG-mode surface policy the swapchain seams read and the controls toggle: whether
	 * frame generation is active, what vsync the reconfigure path must read, and what minimum
	 * image count the swapchain needs for the declared DLSS-G back buffers.
	 */
	val frameGeneration: FgSurfacePolicy = FgSurfacePolicy(),
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
	/**
	 * Blocks until Streamline's DLSS-G input processing for the previously presented frame has
	 * completed, or does nothing for a runtime without FG wiring.
	 *
	 * Runs on the render thread at the start of every FG-active frame, before the world phase
	 * rewrites the DLSS-G-tagged inputs (the scene depth, the native motion image, and the
	 * HUD-less and UI targets). Under the recorded eBlockNoClientQueues mode the plugin reads
	 * those inputs asynchronously after Present, so the wait is what retires the resource-reuse
	 * race between the previous frame's DLSS-G processing and this frame's rewrites. A wait
	 * failure latches the session through the adapter; the routing decision below then reads
	 * the latched state and degrades the frame to vanilla.
	 */
	private val waitForFgInputs: () -> Unit = {},
	/**
	 * Reads the live DLSS-G state for the per-frame status poll, or null for a runtime
	 * without FG wiring or a session that cannot answer.
	 *
	 * Polled on the render thread at the start of every FG-active frame, after the input
	 * wait: while FG is active, a reported status other than eDLSSGStatusOk (word zero)
	 * latches FG off for the session. A null read is no information, not a verdict - a
	 * refused query must not latch the plugin's own status onto its behalf.
	 */
	private val pollFgState: () -> FgState? = { null },
	/**
	 * Re-records the DLSS-G options in the eOff mode (retained resources) when FG switches
	 * off, or does nothing for a runtime without FG wiring: the status latch and the user
	 * toggle both go through it, each recording exactly once on its own transition. The SR
	 * session stays READY, so this is deliberately not a session-latching call.
	 */
	private val recordFgModeOff: () -> Unit = {},
	/** Emits diagnostics; the FG status latch reports its one exact line through this. */
	private val diagnostics: (String) -> Unit = {},
) : AutoCloseable {
	private val resources = FrameResources(sceneTarget, frameEvaluation, quiesce)
	private val phase = WorldPhaseState()
	private val entityIds = IdentityHashMap<EntityRenderState, Int>()
	private var currentViewProjectionState: Matrix4f? = null
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

	/**
	 * Scene-sized velocity view owned by the held scene target, or null when nothing is held.
	 *
	 * This is the color-1 attachment terrain passes bind when the session is on the velocity
	 * route; on a vanilla route or a fallback session nothing is held and the answer is null.
	 */
	val activeVelocityView: GpuTextureView?
		get() = resources.currentVelocityView

	/** Streamline-queried render dimensions, or null until a successful startup. */
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
	 * The world projection and the Streamline evaluation parameter both have to describe the same
	 * offset, so the phase advances the sequence exactly once and publishes the single value
	 * both of them read.
	 */
	val activeJitter: DlssJitterOffset?
		get() = phase.activeJitter

	/** Unjittered current view-projection captured with the world camera sample. */
	val currentViewProjection: Matrix4f?
		get() = currentViewProjectionState

	/**
	 * Camera-only motion for the current world phase, or null outside an eligible DLSS phase and
	 * for an eligible phase that was routed without a camera sample.
	 */
	val activeMotion: DlssFrameMotion?
		get() = phase.activeMotion

	/** Motion-vector path selected for this session after observing world shader ownership. */
	val motionVectorRoute: MotionVectorRoute
		get() = motionVectors.route

	/** Records one pipeline seen at the Vulkan lazy-compile seam while the world phase is open. */
	internal fun observeWorldPipeline(pipeline: MotionVectorPipeline): MotionVectorRoute =
		motionVectors.observe(pipeline)

	/**
	 * The frame-boundary object-motion history: the published predecessors and in-flight
	 * captures behind every dynamic world-pass velocity writer.
	 *
	 * Owned by the phase state, which accumulates and breaks it with the jitter and camera
	 * sequences; this is the read seam the draw path and the lifecycle use.
	 */
	internal val objectMotion: ObjectMotionState
		get() = phase.objectMotion

	/** Resolves the stable id retained for an extracted entity render state. */
	fun entityId(state: EntityRenderState): Int? = entityIds[state]

	/**
	 * Records one visible entity's interpolated render position for the frame in flight.
	 *
	 * Entity extraction runs before the world phase opens, so a capture can land while no
	 * phase is open; a DLSS frame's open keeps it and its completion publishes it, while the
	 * vanilla, abandoned, replaced-world, released, and closed paths reset the history.
	 */
	internal fun captureEntity(id: Int, x: Double, y: Double, z: Double) {
		phase.objectMotion.capture(id, x, y, z)
	}

	internal fun captureEntity(state: EntityRenderState, id: Int, x: Double, y: Double, z: Double) {
		entityIds[state] = id
		phase.objectMotion.capture(id, x, y, z)
	}

	/**
	 * Records one moving block's absolute render position for the frame in flight, in the
	 * moving-block (long-keyed) domain of the shared object history. Same lifecycle and
	 * disposition as [captureEntity]: a DLSS frame's open keeps it and its completion publishes
	 * it, while the vanilla, abandoned, replaced-world, released, and closed paths reset it.
	 */
	internal fun captureBlock(id: Long, x: Double, y: Double, z: Double) {
		phase.objectMotion.capture(id, x, y, z)
	}

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
		// switched off never initializes the bridge at all.
		val started = if (runtimeEnabled) ensureStarted() else false
		if (!started) {
			// No DLSS this session: release any target held from an earlier eligible frame. The
			// primitive also drops the published phase state and resets the sequences, which have
			// nothing to continue into a session that never started.
			releaseFrameState(releaseImages = false)
			return null
		}

		// The frame is about to rewrite the DLSS-G-tagged inputs - the world phase renders into
		// the scene depth, the motion pass overwrites the native motion image, and the split
		// renders into the HUD-less and UI targets - so the previously presented frame's input
		// processing must be complete first. The wait runs on every FG-active frame, whatever
		// this frame's route: a vanilla-routed frame (menu, loading) rewrites the same persistent
		// targets the last FG frame's tags still name. A wait failure latches the session inside
		// the adapter, and the routing decision below then degrades this frame to vanilla.
		if (frameGeneration.active) {
			waitForFgInputs()
			pollFrameGenerationStatus()
		}

		val route = routeFrame(normalInWorldFrame, outputDimensions)
		val target = resources.acquire(route)
		activeRoute = route
		activeWorldTarget = target
		if (target == null) {
			entityIds.clear()
		}
		currentViewProjectionState = if (target != null && camera != null) {
			Matrix4f(camera.projection).mul(camera.viewRotation)
		} else {
			null
		}
		phase.open(target, camera, clock())
		return target
	}

	/**
	 * Closes the world phase. The scene target stays allocated for reuse across frames.
	 *
	 * [completedDlssFrame] is true only after evaluation/composition produced the destination
	 * Minecraft will display. That outcome publishes this frame's entity captures; false resets
	 * them without publication. The default is the safe abandonment/release disposition used by
	 * callers that are not completing an evaluated frame.
	 */
	fun endWorldPhase(completedDlssFrame: Boolean = false) {
		activeRoute = null
		activeWorldTarget = null
		phase.finish(completedDlssFrame)
		currentViewProjectionState = null
		entityIds.clear()
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
		currentViewProjectionState = null
		entityIds.clear()
	}

	/**
	 * Forgets everything this scene accumulated: the camera the next frame would reproject
	 * against and the jitter phase it would continue.
	 *
	 * Used when the scene itself is replaced rather than when one frame was lost.
	 */
	fun resetHistory() {
		phase.reset()
		currentViewProjectionState = null
		entityIds.clear()
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
	 * Switches FG on or off for the frames that follow, and reports whether anything changed.
	 *
	 * The user-toggle half of the M-13 disable path, one seam above the policy so the toggle
	 * and its side effect cannot be separated: the mode transition runs through
	 * [FgSurfacePolicy.setFrameGenerationActive] - invalidating the surface configuration
	 * exactly once when the mode actually changes and restoring the vsync and image-count
	 * reads on the way off - and a transition to off re-records the DLSS-G options in the
	 * eOff mode with retained resources exactly once, through the same non-latching adapter
	 * path the status latch uses. The SR session stays READY and the UI split stays active
	 * either way: switching off degrades nothing, and a user-off policy re-arms on the next
	 * on transition (the first FG frame re-records the eOn options per frame). A re-arm of a
	 * status-latched policy answers false: the user toggle cannot overturn the plugin's own
	 * failure verdict, and the latch's one exact diagnostic stays the only one.
	 */
	fun setFrameGenerationEnabled(enabled: Boolean): Boolean {
		if (!frameGeneration.setFrameGenerationActive(enabled)) {
			return false
		}
		if (!enabled) {
			recordFgModeOff()
		}
		return true
	}

	/**
	 * Re-renders at [mode] with [preset] from the next frame on, and reports whether it took.
	 *
	 * A mode change is a different render size, so everything sized from that size is rebuilt: the
	 * jitter sequence whose length is the pixel ratio, the motion reprojection whose scale is the
	 * render dimensions, the router that decides the scene target's size, the scene target itself,
	 * and the native images the evaluation writes into. A preset change rebuilds none of that and
	 * still goes through here, because the Streamline options are re-recorded on either one.
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
		currentViewProjectionState = null
		entityIds.clear()
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

		// Startup configures the bridge from the session's own configuration, so a mode or preset chosen
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
	 * An eligible DLSS frame renders at the Streamline-queried render dimensions; every vanilla frame
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

	/**
	 * The per-frame DLSS-G status poll: while FG is active, any status other than
	 * eDLSSGStatusOk latches FG off for the session.
	 *
	 * The latch is the plugin's own verdict, so it is deliberately narrow: the policy turns
	 * FG off (restoring vsync and image-count reads and refusing every re-arm), the adapter
	 * re-records the DLSS-G options in the eOff mode with retained resources, the SR session
	 * stays READY, and one exact diagnostic names the status word. A null read latches
	 * nothing. The latch runs once - the policy answers false on the repeat, so the eOff
	 * record and the diagnostic stay attached to the first latch - and the frames that
	 * follow record SR-only through the inactive policy.
	 */
	private fun pollFrameGenerationStatus() {
		val state = pollFgState() ?: return
		if (state.status == FG_STATUS_OK) {
			return
		}
		latchFrameGeneration(state.status)
	}

	private fun latchFrameGeneration(status: Int) {
		if (!frameGeneration.latchOff()) {
			return
		}
		recordFgModeOff()
		diagnostics(
			"Frame generation latched off: slDLSSGGetState status=0x" +
				status.toString(16) +
				" (eDLSSGStatusOk=0); eOff options retained, vsync restored, SR session stays READY, re-arm refused.",
		)
	}

	companion object {
		/**
		 * The DLSS-G status word for a healthy plugin: sl::DLSSGStatus::eOk is zero and every
		 * failure is its own bit, so any non-zero word while FG is active is the status latch's
		 * trigger.
		 */
		private const val FG_STATUS_OK = 0

		/**
		 * Production wiring: Streamline-backed startup against the captured Minecraft Vulkan
		 * context and a Minecraft-allocated scene target. Returns null when no Vulkan context has
		 * been captured yet or the configuration supplies no SDK/data path, because
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
			// One policy for both seams that read the FG mode: the swapchain reconfigure path
			// and the frame evaluation's FG composition. The evaluation reads the same `active`
			// flag the controls toggle, so a mode transition changes the frames that follow
			// exactly when it changes the swapchain policy.
			val frameGeneration = FgSurfacePolicy(
				invalidateSurfaceConfiguration = { Minecraft.getInstance().invalidateSurfaceConfiguration() },
			)
			return RenderRuntime(session, SceneTarget.forMinecraft(), frameEvaluation = FrameEvaluation(
				adapter,
				{ VulkanContextRegistry.getCurrent() },
				readout,
				frameGeneration,
				// The frame's DLSS-G inputs resolve at recording time from the production
				// targets: the main target as the HUD-less colour and the UI phase's held
				// target as the UI colour+alpha, both output-sized (see WorldPhase).
				fgInputs = { WorldPhase.resolveFgInputs() },
			), reconfigure = adapter::reconfigure,
			motionVectors = MotionVectorCompatibility(diagnostics),
			quiesce = { adapter.waitDeviceIdle() },
			// The frame-start wait for the previously presented frame's DLSS-G input processing
			// runs through the same adapter as the frame's recording, on the render thread: the
			// runtime asks before the world phase rewrites the tagged inputs, and a refused or
			// failed wait degrades through the session state exactly like any other native stage.
			waitForFgInputs = { adapter.waitFgInputsIdle() },
			// The per-frame status poll reads the same live DLSS-G state the input wait's
			// fence came from; a non-OK status latches FG off through the policy while the SR
			// session stays READY. The off-transition eOff options record - the status latch's
			// and the user toggle's alike - goes through the same adapter, non-latching like
			// the poll itself.
			pollFgState = { adapter.queryFgState() },
			recordFgModeOff = { adapter.recordFgModeOff() },
			diagnostics = diagnostics,
			// Every FG mode transition recreates the swapchain through Minecraft's own
			// reconfigure path, so the next frame's renderFrame reconfigures the surface under
			// the new policy. The flag itself is only a boolean write, safe from the client
			// thread where the controls toggle it; the reconfigure happens between frames.
			frameGeneration = frameGeneration, startup = {
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
