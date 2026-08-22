package me.snowmii.dlss.render
import me.snowmii.dlss.DlssFrameDecision
import me.snowmii.dlss.DlssFrameRoute
import me.snowmii.streamline.StreamlineSession
import me.snowmii.dlss.readout.FramePacingProbe
import me.snowmii.streamline.VulkanContextRegistry
import me.snowmii.dlss.readout.SessionReadout
import me.snowmii.streamline.Dimensions
import me.snowmii.dlss.SRMode
import me.snowmii.dlss.SRModelPreset
import me.snowmii.dlss.DlssSession
import me.snowmii.dlss.DlssSessionState
import me.snowmii.dlss.DlssStartupConfig
import me.snowmii.dlss.render.mrt.MotionVectorCompatibility
import me.snowmii.dlss.render.mrt.MotionVectorPipeline
import me.snowmii.dlss.render.mrt.MotionVectorRoute
import me.snowmii.dlss.render.mrt.ObjectMotionState
import net.minecraft.client.renderer.entity.state.EntityRenderState
import org.joml.Matrix4f
import java.util.IdentityHashMap
import me.snowmii.dlss.streamline.LifecycleAdapter
import me.snowmii.dlss.streamline.SessionBridge
import net.fabricmc.loader.api.FabricLoader
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
 * Turns a captured Vulkan context into a READY session, then answers which target the world
 * phase renders into. Startup is attempted once, on the first frame that asks for a world
 * target — Streamline needs a live Vulkan device, so this cannot run at mod-init. Failed or
 * skipped startup latches vanilla fallback; later frames are never retried.
 *
 * Does not own GPU objects ([FrameResources]) or jitter/motion/pose sequences
 * ([WorldPhaseState]). What remains: startup latch, configuration in effect, routing.
 *
 * Constructor-injected so the lifecycle is testable off the render thread; [forMinecraft]
 * is production wiring.
 */
class RenderRuntime(
	private val session: DlssSession,
	sceneTarget: SceneTarget,
	private val startup: () -> Dimensions?,
	private val clock: () -> Long = System::nanoTime,
	/**
	 * Records this frame's DLSS work, or null to route targets only. World phase owns *when*;
	 * runtime owns it because it is scoped to the same session.
	 */
	val frameEvaluation: FrameEvaluation? = null,
	/**
	 * Native calls on the running session, or null to route only. Separate from [startup]:
	 * none of these may re-initialize Streamline.
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
			normalInWorldFrame && outputDimensions == session.outputDimensions
		},
	/** Emits diagnostics; the FG status latch reports its one exact line through this. */
	private val diagnostics: (String) -> Unit = {},
) : AutoCloseable {
	private val frameResources = FrameResources(sceneTarget, frameEvaluation) { bridge?.waitDeviceIdle() }
	private val worldPhaseState = WorldPhaseState()
	private val entityIdsByRenderState = IdentityHashMap<EntityRenderState, Int>()
	private var currentWorldViewProjection: Matrix4f? = null
	private var startupAttempted = false

	/** Last main-target size a world frame reported, which the next one has to match to be adopted. */
	private var pendingOutputDimensions: Dimensions? = null

	/** Whether any world frame has reported a main-target size yet; the first one is adopted at once. */
	private var outputDimensionsSeen = false

	/**
	 * Output size DLSS is running at, which follows the client's main target unless the
	 * configuration pinned it. Every readout reports this rather than `config.outputDimensions`.
	 */
	val outputDimensions: Dimensions
		get() = session.outputDimensions

	var qualityMode: SRMode = session.config.qualityMode
		private set

	var renderPreset: SRModelPreset = session.config.renderPreset
		private set

	/**
	 * Whether DLSS is switched on right now, independent of whether the session could ever use it.
	 *
	 * The configuration's own `enabled` decides whether the session has DLSS at all; this decides
	 * whether the user currently wants the route.
	 */
	var dlssEnabled: Boolean = true
		private set

	/**
	 * The FG multiplier in effect, in `numFramesToGenerate` units: 1 = 2x, 2 = 3x, and so on.
	 * Starts at the 2x default and moves only when [setFgMultiplier]'s native record succeeds,
	 * so it always names the multiplier the recorded options carry.
	 *
	 * Read straight off [FgSurfacePolicy] rather than mirrored here: the policy already had to
	 * carry it to size the swapchain's back buffers, and two copies of one multiplier are one
	 * missed assignment away from a swapchain sized for a multiplier the plugin is not
	 * generating at.
	 */
	val fgMultiplier: Int
		get() = frameGeneration.numFramesToGenerate

	val pacing = FramePacingProbe()

	/**
	 * Target the world phase must render into, or null when the frame renders vanilla
	 * full-resolution into Minecraft's main target.
	 */
	@Volatile
	var worldRenderTarget: RenderTarget? = null
		private set

	var worldTargetRoute: WorldTargetRoute? = null
		private set

	/**
	 * Scene-sized velocity view owned by the held scene target, or null when nothing is held.
	 *
	 * This is the color-1 attachment terrain passes bind when the session is on the velocity
	 * route; on a vanilla route or a fallback session nothing is held and the answer is null.
	 */
	val activeVelocityView: GpuTextureView?
		get() = frameResources.currentSceneVelocityView

	var dlssRenderDimensions: Dimensions? = null
		private set

	val config: DlssStartupConfig
		get() = session.config

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
		get() = worldPhaseState.activeJitter

	/** Unjittered current view-projection captured with the world camera sample. */
	val currentViewProjection: Matrix4f?
		get() = currentWorldViewProjection

	/**
	 * Camera-only motion for the current world phase, or null outside an eligible DLSS phase and
	 * for an eligible phase that was routed without a camera sample.
	 */
	val activeMotion: DlssFrameMotion?
		get() = worldPhaseState.activeMotion

	/** Motion-vector path selected for this session after observing world shader ownership. */
	val motionVectorRoute: MotionVectorRoute
		get() = motionVectors.selectedRoute

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

	init {
		// The policy owns every FG-off transition; this pairs it with the session whose options
		// those transitions record. The runtime is the only thing that holds both, and binding it
		// here is what keeps the record from being three `if (transition) record()` pairs at the
		// call sites - the shape where a fourth transition silently skips the record.
		frameGeneration.recordFrameGenerationOff = { bridge?.recordFrameGenerationOff() }
	}

	/** Records one pipeline seen at the Vulkan lazy-compile seam while the world phase is open. */
	internal fun observeWorldPipeline(pipeline: MotionVectorPipeline): MotionVectorRoute {
		val previous = motionVectors.selectedRoute
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
		get() = worldPhaseState.objectMotion

	/** Resolves the stable id retained for an extracted entity render state. */
	fun entityId(state: EntityRenderState): Int? = entityIdsByRenderState[state]

	/**
	 * Records one visible entity's interpolated render position for the frame in flight.
	 *
	 * Entity extraction runs before the world phase opens, so a capture can land while no
	 * phase is open; a DLSS frame's open keeps it and its completion publishes it, while the
	 * vanilla, abandoned, replaced-world, released, and closed paths reset the history.
	 */
	internal fun captureEntity(id: Int, x: Double, y: Double, z: Double) {
		worldPhaseState.objectMotion.capturePosition(id, x, y, z)
	}

	internal fun captureEntity(state: EntityRenderState, id: Int, x: Double, y: Double, z: Double) {
		entityIdsByRenderState[state] = id
		worldPhaseState.objectMotion.capturePosition(id, x, y, z)
	}

	/**
	 * Records one moving block's absolute render position for the frame in flight, in the
	 * moving-block (long-keyed) domain of the shared object history. Same lifecycle and
	 * disposition as [captureEntity]: a DLSS frame's open keeps it and its completion publishes
	 * it, while the vanilla, abandoned, replaced-world, released, and closed paths reset it.
	 */
	internal fun captureBlock(id: Long, x: Double, y: Double, z: Double) {
		worldPhaseState.objectMotion.capturePosition(id, x, y, z)
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
		val started = if (dlssEnabled) startDlssOnce() else false

		// The client's main target is the authority on the output size, so a frame at a size the
		// session is not configured against reconfigures it rather than routing vanilla forever.
		// This runs before the FG classifier and the route: both read the session's size, and the
		// reconfigure suspends FG and releases the images itself.
		if (started) {
			adoptOutputDimensions(outputDimensions)
		}

		// No wait on DLSSGState::inputsProcessingCompletionFence. That wait is required only
		// under eBlockNoClientQueues; Minecraft renders and presents on one queue, so options
		// use eBlockPresentingClientQueue and the plugin blocks that queue itself.
		// [me.snowmii.streamline.StreamlineSession.waitFgInputsIdle] stays on the ABI for the
		// other mode. Poll the user's mode, not the effective one: an unhealthy status suspends
		// composition, and gating the poll on composition would never observe recovery.
		if (frameGeneration.userEnabled) {
			pacing.begin(FramePacingProbe.Span.FG_STATUS_POLL)
			updateFrameGenerationHealth()
			pacing.end(FramePacingProbe.Span.FG_STATUS_POLL)
		}

		// FG composes only on supported in-world frames. Classifier runs after the health poll
		// so a suspending frame still read status. supported→unsupported records retained eOff
		// once; in-between frames are SR-only; a supported frame resumes without a record
		// (the next FG frame's per-frame options re-record eOn).
		//
		// SR-off is unsupported before the classifier: DLSS-G reads scene depth, the bridge
		// motion image, and output-sized HUD-less colour, and switching SR off releases those.
		// Returning not-started first left mode eOn with tags naming released images, which
		// intercepted present turned into VK_ERROR_DEVICE_LOST.
		val frameSupported =
			started && fgStatusHealthy && fgFrameSupported(normalInWorldFrame, outputDimensions)
		frameGeneration.setCompositionSupported(frameSupported)

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

		val route = chooseWorldTargetRoute(normalInWorldFrame, outputDimensions)
		val target = frameResources.acquire(route)
		worldTargetRoute = route
		worldRenderTarget = target
		if (target == null) {
			entityIdsByRenderState.clear()
		}
		currentWorldViewProjection = if (target != null && camera != null) {
			Matrix4f(camera.projection).mul(camera.viewRotation)
		} else {
			null
		}
		worldPhaseState.open(target, camera, clock())
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
		worldTargetRoute = null
		worldRenderTarget = null
		worldPhaseState.finish(completedDlssFrame)
		currentWorldViewProjection = null
		entityIdsByRenderState.clear()
	}

	/**
	 * Forgets the camera the next frame would reproject against.
	 *
	 * A frame that decided its route but never finished rendering still moved the predecessor
	 * forward. Nothing accumulated it, so the frame after it must not measure motion from a
	 * camera no image was ever produced for.
	 */
	fun resetMotionHistory() {
		worldPhaseState.resetMotion()
		currentWorldViewProjection = null
		entityIdsByRenderState.clear()
	}

	/**
	 * Forgets everything this scene accumulated: the camera the next frame would reproject
	 * against and the jitter phase it would continue.
	 *
	 * Used when the scene itself is replaced rather than when one frame was lost.
	 */
	fun resetHistory() {
		worldPhaseState.reset()
		currentWorldViewProjection = null
		entityIdsByRenderState.clear()
	}

	/**
	 * Switches DLSS on or off for the frames that follow, and reports whether anything changed.
	 *
	 * Switching off is the full-resolution path the contract already requires of a failure, minus
	 * the failure: the low-resolution target is released, the native images go with it, and the
	 * accumulated history is dropped, because the frames that come back are not continuous with
	 * the ones that stopped.
	 */
	fun setDlssEnabled(enabled: Boolean): Boolean {
		if (enabled == dlssEnabled) {
			return false
		}

		dlssEnabled = enabled
		releaseFrameState(releaseImages = true)
		return true
	}

	/**
	 * Switches FG on or off for the frames that follow, and reports whether anything changed.
	 *
	 * [FgSurfacePolicy] owns the whole transition - invalidating the surface configuration exactly
	 * once when the mode actually
	 * changes, restoring the vsync and image-count reads on the way off, and re-recording the
	 * DLSS-G options in the eOff mode with retained resources - so the toggle and its side
	 * effects cannot be separated by the caller that drives it. The SR session stays READY and
	 * the UI split stays active either way: switching off degrades nothing, and a user-off
	 * policy re-arms on the next on transition (the first FG frame re-records the eOn options
	 * per frame).
	 */
	fun setFrameGenerationEnabled(enabled: Boolean): Boolean =
		frameGeneration.setFrameGenerationActive(enabled)

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
		// The policy is the multiplier's one owner, and its back-buffer count is derived from it,
		// so it moves before the invalidation that recreates the swapchain: a higher multiplier
		// presents more frames per app frame and needs the images to hold them.
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
		// Unconditional teardown wait first, then the guarded release: see FrameResources.close.
		endWorldPhase()
		frameResources.close()
		worldPhaseState.reset()
		// Before the session closes: releasing the native images needs a session still READY.
		worldPhaseState.discard()
		dlssRenderDimensions = null
		currentWorldViewProjection = null
		entityIdsByRenderState.clear()
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
			frameGeneration.setCompositionSupported(false)
		}
		endWorldPhase()
		frameResources.release(releaseImages)
		worldPhaseState.reset()
	}

	/**
	 * Runs native startup at most once and returns whether DLSS is available for this session.
	 */
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

		rebuildForRenderDimensions(dimensions)
		return true
	}

	/**
	 * Moves the session onto [dimensions] when the client has settled there, and reports whether
	 * the native side was reconfigured.
	 *
	 * A drag-resize reports a different size every frame and each reconfigure costs a device-idle
	 * release, so a new size has to be seen twice in a row before it is adopted: the frames during
	 * the drag route vanilla at their own size, and the first stable size pays one reconfigure.
	 * The very first world frame is exempt - the session has rendered nothing yet, so there is no
	 * running configuration to protect and no reason to spend a vanilla frame.
	 *
	 * A pinned session ([DlssStartupConfig.outputPinned]) adopts nothing, and a refused native
	 * reconfigure puts the session back on the size it was actually running at, because the frames
	 * that follow are still the old configuration's.
	 */
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

	/**
	 * Rebuilds everything sized from the render dimensions after they change: the two sequences
	 * the phase accumulates, and the render dimensions themselves, which are the routing
	 * decision's one source of truth.
	 */
	private fun rebuildForRenderDimensions(dimensions: Dimensions) {
		dlssRenderDimensions = dimensions
		worldPhaseState.rebuild(dimensions, session.outputDimensions)
	}

	/**
	 * Decides this frame's world target size from the session's route.
	 *
	 * An eligible DLSS frame renders at the Streamline-queried render dimensions; every vanilla frame
	 * renders at the output size. The render dimensions are this runtime's own field, so there is
	 * exactly one copy of them for the whole route path.
	 */
	private fun chooseWorldTargetRoute(normalInWorldFrame: Boolean, outputDimensions: Dimensions): WorldTargetRoute {
		val frame = session.beginFrame(normalInWorldFrame, outputDimensions)
		// A DLSS route is only possible once startup set the render dimensions; a route that is
		// somehow DLSS without them degrades to the output size rather than a null target.
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
	 * Routed through the frame-support axis: composition suspends, the retained eOff record
	 * attaches to the transition as it does for a pause or menu, swapchain policy does not
	 * move (see [FgSurfacePolicy]), and the first healthy frame resumes.
	 */
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
		 * been captured yet.
		 */
		fun forMinecraft(
			session: DlssSession,
			native: StreamlineSession,
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
				fgInputs = { WorldPhase.resolveFrameGenerationInputs() },
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
					outputMatches = outputDimensions == session.outputDimensions,
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
