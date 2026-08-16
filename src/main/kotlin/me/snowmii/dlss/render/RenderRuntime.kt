package me.snowmii.dlss.render
import me.snowmii.dlss.session.DlssFrameDecision
import me.snowmii.dlss.session.DlssFrameRoute
import me.snowmii.streamline.NativeApi
import me.snowmii.dlss.readout.AcceptanceRecord
import me.snowmii.dlss.readout.FramePacingProbe
import me.snowmii.dlss.bridge.VulkanContextRegistry
import me.snowmii.dlss.readout.SessionReadout
import me.snowmii.streamline.Dimensions
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
import me.snowmii.dlss.session.SessionBridge
import me.snowmii.dlss.fg.FgSurfacePolicy
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.LoadingOverlay
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.textures.GpuTextureView

/** Dimension policy consumed by renderer hooks before world target allocation. */
data class WorldTargetRoute(
	val frame: DlssFrameDecision,
	val worldDimensions: Dimensions,
	val mainTargetDimensions: Dimensions,
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
	private val startup: () -> Dimensions?,
	private val clock: () -> Long = System::nanoTime,
	/**
	 * Records this frame's DLSS work, or null for a runtime that only routes targets. The world
	 * phase owns *when* it runs; the runtime owns it because it is scoped to the same session.
	 */
	val frameEvaluation: FrameEvaluation? = null,
	/**
	 * Every native call this runtime makes on the running session - the reconfigure, the device
	 * stall, the FG input wait, the status poll, the eOff record, and the multiplier pair - or
	 * null for a runtime that only routes targets and never reaches the native side.
	 *
	 * One collaborator rather than seven lambdas over it: see [SessionBridge]. Separate from
	 * [startup] because none of these may re-initialize the Streamline session.
	 */
	private val bridge: SessionBridge? = null,
	/** Session compatibility latch populated by world-pipeline compilation. */
	private val motionVectors: MotionVectorCompatibility = MotionVectorCompatibility(),
	/**
	 * The FG-mode surface policy the swapchain seams read and the controls toggle: whether
	 * frame generation is active, what vsync the reconfigure path must read, and what minimum
	 * image count the swapchain needs for the declared DLSS-G back buffers.
	 */
	val frameGeneration: FgSurfacePolicy = FgSurfacePolicy(),
	/**
	 * Invalidates Minecraft's surface configuration once per real multiplier change, so the
	 * next frame recreates the swapchain under the options the new multiplier records.
	 */
	private val invalidateSurfaceConfiguration: () -> Unit = {},
	/**
	 * Classifies whether the current frame may compose FG: a real (non-panorama) world frame
	 * at the configured output size, plus whatever client state the production wiring adds
	 * (pause, loading overlay, open screen, fullscreen flip). A false answer suspends the
	 * effective FG mode through the policy exactly once per transition; a true answer
	 * resumes it. Defaults to the two signals [beginWorldPhase] itself carries, so runtimes
	 * without client wiring - tests, target-only sessions - classify by frame and size.
	 */
	private val fgFrameSupported: (Boolean, Dimensions) -> Boolean =
		{ normalInWorldFrame, outputDimensions ->
			normalInWorldFrame && outputDimensions == session.config.outputDimensions
		},
	/** Emits diagnostics; the FG status latch reports its one exact line through this. */
	private val diagnostics: (String) -> Unit = {},
) : AutoCloseable {
	private val resources = FrameResources(sceneTarget, frameEvaluation) { bridge?.waitDeviceIdle() }
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
	 * The FG multiplier in effect, in `numFramesToGenerate` units: 1 = 2x, 2 = 3x, and so on.
	 * Starts at the 2x default and moves only when [setFgMultiplier]'s native record succeeds,
	 * so it always names the multiplier the recorded options carry.
	 */
	var fgMultiplier: Int = 1
		private set

	/**
	 * Where each app frame's wall time goes, sampled by the seams that can stall one and reported
	 * on the frame-rate line. See [FramePacingProbe].
	 */
	val pacing = FramePacingProbe()

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
	var renderDimensions: Dimensions? = null
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

	/**
	 * Set when the compatibility latch flips, consumed at the next phase start.
	 *
	 * Not applied where the flip happens: Vulkan compiles a pipeline lazily, at its first draw,
	 * so the latch flips in the middle of a frame whose phase is already open. Resetting there
	 * would clear that frame's camera out from under its own velocity writers, and the frame
	 * would lose the motion it was rendering with.
	 */
	private var motionRouteChanged = false

	/**
	 * Whether the plugin's last reported status was `eOk`, which composition is gated on.
	 *
	 * Starts healthy: a session that has never polled has nothing to suspend for, and the first
	 * poll of an unhealthy plugin suspends on its own transition.
	 */
	private var fgStatusHealthy = true

	/** Records one pipeline seen at the Vulkan lazy-compile seam while the world phase is open. */
	internal fun observeWorldPipeline(pipeline: MotionVectorPipeline): MotionVectorRoute {
		val previous = motionVectors.route
		val route = motionVectors.observe(pipeline)
		if (route != previous) {
			motionRouteChanged = true
		}
		return route
	}

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
		outputDimensions: Dimensions,
		camera: DlssCameraSample? = null,
	): RenderTarget? {
		// Switched off takes effect before startup is ever attempted, so a session that begins
		// switched off never initializes the bridge at all.
		val started = if (runtimeEnabled) ensureStarted() else false

		// No wait on DLSSGState::inputsProcessingCompletionFence here any more, and the measurement
		// is why: it cost 10-11ms of every 13ms FG frame, which is the whole difference between
		// ~450fps with FG off and ~75 with it on. The wait is required only under the
		// eBlockNoClientQueues queue-parallelism mode, whose documented gains are for applications
		// submitting from several queues; Minecraft renders and presents on one. The recorded
		// options are eBlockPresentingClientQueue now, under which the guide makes the wait
		// "recommended but not required" when the tagged inputs are modified on the presenting
		// queue - the only queue this frame has - and the plugin blocks that queue itself for as
		// long as it actually needs. [me.snowmii.streamline.NativeApi.waitFgInputsIdle] stays on
		// the ABI: it is the mode's obligation, and the mode is one options field away.
		// Polled on the user's mode rather than the effective one: an unhealthy status suspends
		// composition, and gating the poll on composition would stop the polling that observes the
		// status becoming healthy again - the suspension would never lift.
		if (frameGeneration.armed) {
			pacing.begin(FramePacingProbe.Span.FG_STATUS_POLL)
			pollFrameGenerationStatus()
			pacing.end(FramePacingProbe.Span.FG_STATUS_POLL)
		}

		// FG composes only on supported in-world frames. The classifier runs after the
		// FG-active wait and poll, so the frame that suspends has still waited out the
		// previous FG frame's input processing and read its status; a supported->unsupported
		// transition then records the retained eOff mode exactly once, the frames in between
		// compose SR-only, and a supported frame resumes without a record - the next FG
		// frame's per-frame options record re-records eOn.
		//
		// A frame SR is not running is unsupported before the classifier is even asked: DLSS-G
		// reads the scene depth, the bridge's motion image, and the output-sized HUD-less
		// colour, and switching SR off releases exactly those. The classifier runs ahead of the
		// not-started return for that reason - returning first left the mode eOn with tags
		// naming released images, which the intercepted present turned into
		// VK_ERROR_DEVICE_LOST.
		val frameSupported =
			started && fgStatusHealthy && fgFrameSupported(normalInWorldFrame, outputDimensions)
		if (frameGeneration.setFrameSupported(frameSupported) && !frameSupported) {
			bridge?.recordFgModeOff()
		}

		if (!started) {
			// No DLSS this session: release any target held from an earlier eligible frame. The
			// primitive also drops the published phase state and resets the sequences, which have
			// nothing to continue into a session that never started.
			releaseFrameState(releaseImages = false)
			return null
		}

		// The frames before the latch flipped were evaluated against a motion image a different
		// writer produced, and DLSS still holds the history they accumulated. The writer changing
		// is the same kind of discontinuity as a replaced scene - the reprojection the history
		// describes is not the one the frames that follow will produce - so the history breaks
		// here, at the first phase start after the flip, where clearing it cannot strand an open
		// frame.
		if (motionRouteChanged) {
			motionRouteChanged = false
			resetHistory()
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
			bridge?.recordFgModeOff()
		}
		return true
	}

	/**
	 * Records a new FG multiplier and reports whether it took.
	 *
	 * A real change is the one seam where the surface reconfigure and the native record
	 * cannot separate: the record runs first, and only a successful record moves
	 * [fgMultiplier], updates the acceptance record's active multiplier, and invalidates the
	 * surface configuration exactly once. A refused record (the device ceiling says no, or
	 * the session cannot answer) changes nothing - the multiplier, the record holder, and
	 * the surface all stay exactly as they were.
	 */
	fun setFgMultiplier(numFramesToGenerate: Int): Boolean {
		if (numFramesToGenerate == fgMultiplier || numFramesToGenerate < 1) {
			return false
		}
		if (bridge?.setFgMultiplier(numFramesToGenerate) != true) {
			return false
		}
		fgMultiplier = numFramesToGenerate
		AcceptanceRecord.activeFgMultiplier = numFramesToGenerate
		// The swapchain policy's back-buffer count is derived from the multiplier, so it moves
		// before the invalidation that recreates the swapchain: a higher multiplier presents more
		// frames per app frame and needs the images to hold them (see FgSurfacePolicy).
		frameGeneration.numFramesToGenerate = numFramesToGenerate
		invalidateSurfaceConfiguration()
		return true
	}

	/**
	 * Cycles the FG multiplier from 2x up through the device ceiling and back to 2x.
	 *
	 * The next value is computed from the bridge's own read (the stored multiplier and the
	 * device's numFramesToGenerateMax): 2x, 3x, ... ceiling, wrap to 2x. A device whose
	 * ceiling is 2x (max = 1) is a no-op cycle, and a current value at or above the ceiling
	 * wraps back down - an unsupported multiplier is never offered, and a refusal by the
	 * native record leaves the runtime on the multiplier it was already running.
	 */
	fun cycleFgMultiplier(): Boolean {
		val state = bridge?.queryFgMultiplier() ?: return false
		val next = if (state.max <= 1 || state.current >= state.max) 1 else state.current + 1
		return setFgMultiplier(next)
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

		val dimensions = bridge?.reconfigure(mode, preset) ?: return false
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
		if (releaseImages) {
			// The images about to be destroyed are the ones the last FG frame's tags name, and
			// DLSS-G reads its tags at present time, not at record time: releasing them with the
			// mode still eOn hands the intercepted present dead handles, which is the
			// VK_ERROR_DEVICE_LOST that disabling SR - or changing quality mode - produced while
			// FG was on. Suspending through the frame-support seam rather than the user's mode
			// keeps this reversible: the mode itself is untouched, so the first supported frame
			// after the images come back resumes FG without the reviewer re-arming anything.
			if (frameGeneration.setFrameSupported(false)) {
				bridge?.recordFgModeOff()
			}
		}
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
			bridge?.reconfigure(qualityMode, renderPreset) ?: run {
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
	private fun rebuildFrom(dimensions: Dimensions) {
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
	private fun routeFrame(normalInWorldFrame: Boolean, outputDimensions: Dimensions): WorldTargetRoute {
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
	 * Reads the plugin's status and suspends composition while it is unhealthy.
	 *
	 * `sl::DLSSGStatus` is a bitmask of conditions the plugin is reporting about *this frame*, not
	 * a verdict about the session: `eFailResolutionTooLow` holds while the back buffers are too
	 * small, `eFailCommonConstantsInvalid` while a frame's constants have not settled, and
	 * `eFailReflexNotDetectedAtRuntime` while the plugin has not observed Reflex yet. Every one of
	 * them clears when the condition does, and a quality-mode or preset change produces them for a
	 * frame or two by construction: the reconfigure rebuilds the images and the resolution, and the
	 * plugin reports on the frames in between.
	 *
	 * This used to latch FG off for the whole session on the first non-zero word, which also
	 * restored vsync - so one transient bit during a settings change ended frame generation and put
	 * the swapchain back on FIFO for good, with no way back short of a restart. It is routed
	 * through the frame-support axis instead: composition suspends, the retained eOff record
	 * attaches to the transition exactly as it does for a pause or a menu, the swapchain policy
	 * deliberately does not move (see [FgSurfacePolicy]), and the first healthy frame resumes.
	 */
	private fun pollFrameGenerationStatus() {
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
		/**
		 * The DLSS-G status word for a healthy plugin: sl::DLSSGStatus::eOk is zero and every
		 * failure is its own bit, so any non-zero word while FG is active is the status latch's
		 * trigger.
		 */
		private const val FG_STATUS_OK = 0

		/**
		 * The FG frame-support classification: a frame may compose FG only when it is a real
		 * in-world frame at the configured output size and the client is not paused, loading,
		 * showing a screen, or inside the one frame that observes a fullscreen/windowed flip.
		 */
		fun isFgFrameSupported(
			normalInWorldFrame: Boolean,
			outputMatches: Boolean,
			paused: Boolean,
			loading: Boolean,
			screenOpen: Boolean,
			fullscreenTransition: Boolean,
		): Boolean = normalInWorldFrame && outputMatches && !paused && !loading && !screenOpen && !fullscreenTransition

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
			// The requested window mode as of the previous world frame, so the classifier can
			// name the one frame a fullscreen/windowed flip lands on; null before the first
			// frame, which never reads as a transition.
			var lastFullscreen: Boolean? = null
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
			),
			// Every native call a frame makes - the reconfigure, the device stall, the FG input
			// wait, the status poll, the eOff record, and the multiplier pair - goes through the
			// same adapter as the frame's recording, on the render thread. The wait and the
			// reconfigure degrade through the session state like any other native stage; the FG
			// records deliberately do not (see SessionBridge and LifecycleAdapter).
			bridge = adapter,
			motionVectors = MotionVectorCompatibility(diagnostics),
			// The F12 multiplier cycle invalidates the surface configuration once per real change
			// through Minecraft's own reconfigure path.
			invalidateSurfaceConfiguration = { Minecraft.getInstance().invalidateSurfaceConfiguration() },
			diagnostics = diagnostics,
			// The frame-support classifier's client half: the world-phase entry's own signals
			// plus the client state that names the unsupported frames. The fullscreen check
			// compares the requested mode against the previous world frame's, which catches
			// the one frame a fullscreen/windowed flip lands on even when the surface size
			// does not change.
			fgFrameSupported = { normalInWorldFrame, outputDimensions ->
				val client = Minecraft.getInstance()
				val fullscreen = client.window.isFullscreen
				val transition = lastFullscreen != null && lastFullscreen != fullscreen
				lastFullscreen = fullscreen
				isFgFrameSupported(
					normalInWorldFrame = normalInWorldFrame,
					outputMatches = outputDimensions == session.config.outputDimensions,
					paused = client.isPaused,
					loading = client.gui.overlay() is LoadingOverlay,
					screenOpen = client.gui.screen() != null,
					fullscreenTransition = transition,
				)
			},
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
