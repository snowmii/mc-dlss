package me.snowmii.dlss.render
import me.snowmii.dlss.bridge.ImageBinding
import me.snowmii.dlss.client.ClientRuntime
import me.snowmii.dlss.readout.SessionFacts
import me.snowmii.dlss.readout.SessionReadout
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.mrt.MotionVectorPipeline
import me.snowmii.dlss.mrt.MotionVectorRoute
import net.minecraft.client.renderer.entity.state.EntityRenderState
import org.joml.Matrix4f
import org.joml.Vector3f
import me.snowmii.dlss.session.DlssFrameDecision
import me.snowmii.dlss.session.DlssFrameRoute
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.vulkan.VulkanConst
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import java.util.Optional

/**
 * Scopes one world render phase, which is the only window in which the mod's low-resolution
 * scene target stands in for Minecraft's main target.
 *
 * [RenderRuntime] decides *whether* a frame is eligible and *what size* its target is.
 * This class decides *when* that target is visible to the renderer: the phase is opened at the
 * head of `LevelRenderer.render` and closed at its tail, and while it is open
 * [worldTargetOverride] is what `GameRenderer.mainRenderTarget()` answers. The world scene and
 * stress pass land in the low-resolution target; hand and item, screen effects, the 3D crosshair,
 * post chains, GUI clear, screenshots, and presentation happen after evaluation against the
 * full-size main target.
 *
 * Closing an eligible phase evaluates DLSS and composes the upscaled result into the main target,
 * which is what everything drawn afterwards renders on top of at output resolution. A frame whose
 * evaluation produced nothing falls back to the nearest-neighbour blit of the low-resolution scene
 * instead: deliberately *not* an upscale, so a failed frame is visibly the internal scene
 * resolution rather than a black screen.
 *
 * Presentation and the sky-renderer reset are injected so the whole phase is verifiable off the
 * render thread; [forMinecraft] supplies the production wiring.
 */
class WorldPhase(
	private val runtime: RenderRuntime,
	private val present: (RenderTarget, RenderTarget) -> Unit,
	private val onWorldTargetChanged: () -> Unit,
	/**
	 * Records this frame's DLSS work against the scene it just rendered and composes the result
	 * into the destination, returning true when the destination now holds the upscaled frame.
	 *
	 * Injected because reaching the raw Vulkan handles behind a target needs Minecraft's backend
	 * types, and everything else here is verifiable off the render thread. [route] is the
	 * session's world-motion route and [velocityView] the scene velocity companion view behind
	 * it, captured at close time so the evaluation can feed the velocity MRT to Streamline on
	 * the velocity route and keep the compute writer on the camera-only route. [camera] is the
	 * frame's camera as the projection seam sampled it, snapshotted when [prepare] stored it
	 * (the seam's matrices are reused across frames) and read at close time: the phase clears
	 * its own field as it closes, so the sample travels as a parameter rather than as a field
	 * the production wiring would read after the clear.
	 */
	private val evaluateFrame: (RenderTarget, RenderTarget, DlssJitterOffset, DlssFrameMotion, MotionVectorRoute, GpuTextureView?, DlssCameraSample?) -> Boolean =
		{ _, _, _, _, _, _, _ -> false },
	/**
	 * Formats and emits the session's reporting lines, fed by this phase and by the evaluation.
	 */
	private val readout: SessionReadout = SessionReadout.NOOP,
) : AutoCloseable {
	private var scene: RenderTarget? = null
	private var mainTarget: RenderTarget? = null
	private var prepared = false
	private var lastResolved: RenderTarget? = null
	/** The frame's camera as the projection seam sampled it, snapshotted at [prepare] and carried to [end]. */
	private var camera: DlssCameraSample? = null

	/** True between [begin] and [end]. */
	var isOpen: Boolean = false
		private set

	/**
	 * Target `GameRenderer.mainRenderTarget()` must answer with, or null when the caller gets
	 * the vanilla main target. Non-null only inside an eligible DLSS world phase.
	 */
	val worldTargetOverride: RenderTarget?
		get() = if (isOpen) scene else null

	/**
	 * Observes a pipeline whose ownership can change the session's world-motion route.
	 *
	 * Two callers use this seam, both inside the open world phase: the Vulkan lazy-compile
	 * mixin observes every pipeline entering compile as a backstop, and the terrain mixin's
	 * {@code renderGroup} HEAD inject classifies a group's source pipelines before the pass
	 * shape is chosen, so a first-encounter foreign pipeline keeps exact vanilla passthrough
	 * instead of binding into a two-attachment pass. Shader reload, GUI, post-processing, and
	 * presentation pipelines run outside this window and cannot change the session's
	 * world-motion route.
	 */
	fun presentStart(): Boolean = runtime.frameEvaluation?.presentStart() == true
	fun presentEnd(): Boolean = runtime.frameEvaluation?.presentEnd() == true

	// The five Reflex/PCL frame markers of the M-12 surface, reached the same way the
	// present bracket is: the Minecraft run/runTick/renderFrame mixins call the active
	// world phase (the render loop's handle to the runtime, null before the DLSS path was
	// built), and this object passes the call through to the evaluation and its adapter.
	// The input and simulation seams fire outside the world phase's own window - before
	// LevelRenderer.render opens it - which is fine: the phase object exists for the whole
	// session once the render loop built the path, and the markers themselves are gated on
	// the READY session inside the adapter, not on the phase being open.
	fun reflexInputSample(): Boolean = runtime.frameEvaluation?.reflexInputSample() == true
	fun reflexSimulateStart(): Boolean = runtime.frameEvaluation?.reflexSimulateStart() == true
	fun reflexSimulateEnd(): Boolean = runtime.frameEvaluation?.reflexSimulateEnd() == true
	fun reflexRenderSubmitStart(): Boolean = runtime.frameEvaluation?.reflexRenderSubmitStart() == true
	fun reflexRenderSubmitEnd(): Boolean = runtime.frameEvaluation?.reflexRenderSubmitEnd() == true

	fun observePipeline(pipeline: MotionVectorPipeline) {
		if (isOpen) {
			runtime.observeWorldPipeline(pipeline)
		}
	}

	/**
	 * Records one visible entity's interpolated render position for the frame in flight, keyed
	 * by the entity's stable id.
	 *
	 * The capture mixes in from `LevelExtractor.extractVisibleEntities`, which runs before
	 * [begin] opens the phase, so - unlike [observePipeline] - this is deliberately not gated on
	 * [isOpen]: the in-flight captures must land while the phase is still closed, and only a
	 * DLSS frame's own open keeps them. A vanilla frame's open, an abandoned phase, a world
	 * change, a release, and a close all reset the history instead; a successful world-phase
	 * completion publishes the frame's captures exactly once at the boundary.
	 */
	fun captureEntity(id: Int, x: Double, y: Double, z: Double) {
		runtime.captureEntity(id, x, y, z)
	}

	/** Records the returned render-state identity alongside its stable entity id and pose. */
	fun captureEntity(state: EntityRenderState, id: Int, x: Double, y: Double, z: Double) {
		runtime.captureEntity(state, id, x, y, z)
	}

	/**
	 * Records one moving block's absolute render position for the frame in flight, keyed by the
	 * packed long block-position identity of its baked position.
	 *
	 * The piston capture seam calls this at the block-entity dispatcher, before [begin] opens
	 * the phase, so - like [captureEntity] - this is deliberately not gated on [isOpen]: the
	 * in-flight captures must land while the phase is still closed, and only a DLSS frame's own
	 * open keeps them. The key is a long in the moving-block domain, resolved by the long
	 * overload of the shared object history, so an entity id with the same numeric value can
	 * never read or write this block's slot.
	 */
	fun captureBlock(id: Long, x: Double, y: Double, z: Double) {
		runtime.captureBlock(id, x, y, z)
	}

	/** Resolves the stable id paired with one extracted state while this phase is open. */
	fun entityId(state: EntityRenderState): Int? = if (isOpen) runtime.entityId(state) else null

	/** Whether an entity-model draw may use the scene velocity attachment. */
	val entityVelocityActive: Boolean
		get() = isOpen && runtime.motionVectorRoute == MotionVectorRoute.VELOCITY_MRT && terrainVelocityView != null

	/** Jitter used by this open world's projection, for object reprojection composition. */
	val activeJitter: DlssJitterOffset?
		get() = if (isOpen) runtime.activeJitter else null

	/** Unjittered current view-projection captured at the world projection seam. */
	val currentViewProjection: Matrix4f?
		get() = if (isOpen) runtime.currentViewProjection else null

	/** Current captured-minus-previous displacement for one entity, or null without a predecessor. */
	fun objectMotionDisplacement(entityId: Int): Vector3f? =
		if (entityVelocityActive) runtime.objectMotion.displacement(entityId) else null

	/**
	 * Current captured-minus-previous displacement for one moving block, or null without a
	 * predecessor. Gated by the same shared velocity-active condition as
	 * [objectMotionDisplacement] - an open velocity-MRT phase with a scene velocity view -
	 * and resolved in the moving-block domain of the shared object history.
	 */
	fun blockMotionDisplacement(blockId: Long): Vector3f? =
		if (entityVelocityActive) runtime.objectMotion.displacement(blockId) else null

	/**
	 * The scene-sized RG16_FLOAT velocity view terrain chunk passes must render into, or null
	 * when those passes stay vanilla.
	 *
	 * Non-null only inside an open world phase that latched the velocity-MRT route and holds a
	 * scene target with a velocity companion: exactly the frames on which
	 * `ChunkSectionsToRender.renderGroup` may add the velocity attachment at color index 1 and
	 * bind velocity twins. A closed phase, the camera-only fallback route, or a frame without a
	 * companion all answer null, which keeps pass creation and source-pipeline binding exactly
	 * vanilla - and because every read here is a plain field or enum read, the fallback path
	 * cannot throw.
	 */
	val terrainVelocityView: GpuTextureView?
		get() = if (isOpen && runtime.motionVectorRoute == MotionVectorRoute.VELOCITY_MRT) {
			runtime.activeVelocityView
		} else {
			null
		}

	/**
	 * This frame's published camera motion while the phase is open, or null outside one.
	 *
	 * Read by the stress pass at the tail of the world phase, before [end] consumes the value:
	 * the reprojection it derives velocity from is the same jitter-stripped current-to-previous
	 * clip reprojection the evaluation receives, so the velocity buffer and the DLSS evaluation
	 * describe the same camera motion. A closed phase, a vanilla route, or a frame whose camera
	 * was never observed all answer null; every read here is a plain field read, so the fallback
	 * path cannot throw.
	 */
	val activeMotion: DlssFrameMotion?
		get() = if (isOpen) runtime.activeMotion else null

	/**
	 * Decides this frame's route and jitter without opening the phase, and returns the jitter
	 * an eligible DLSS frame must apply to its world projection, or null for a vanilla frame.
	 *
	 * Minecraft uploads the world projection in `GameRenderer.renderLevel`, before
	 * `LevelRenderer.render` runs, so the route has to be known earlier than the phase can be
	 * open - the window starts at the head of `LevelRenderer.render` and ends right after the
	 * hand draw, and nothing before the world scene renders under the low-resolution override.
	 * Splitting the decision from the window is what keeps both true, and the route is still
	 * decided exactly once per frame because [begin] consumes this preparation rather than
	 * repeating it.
	 *
	 * [camera] is the frame's camera as the projection seam sees it, and is what the runtime
	 * derives camera-only motion from. The phase snapshots the sample's matrices before
	 * storing it, because Minecraft reuses them across frames: the caller's original may keep
	 * changing after [prepare] returns without reaching the evaluation. It is null only when
	 * the phase is opened without the projection seam having run, which publishes no motion
	 * for that frame.
	 */
	fun prepare(
		normalInWorldFrame: Boolean,
		mainTarget: RenderTarget,
		camera: DlssCameraSample? = null,
	): DlssJitterOffset? {
		// An exception thrown inside the world phase - LevelRenderer.render, or the hand draw
		// before WorldPhase.end - skips the close, and a frame that prepared but never rendered
		// leaves one unconsumed. Both are dropped here rather than thrown on, because a stale
		// phase must not turn one render failure into a permanent one.
		discard()

		this.mainTarget = mainTarget
		// Minecraft reuses the sample's matrices across frames, so the stored sample must not
		// reference the seam's live ones: snapshot before storing, and the evaluation reads
		// this frame's camera no matter what the renderer rewrites before the phase closes.
		val snapshot = camera?.let {
			DlssCameraSample(
				projection = Matrix4f(it.projection),
				viewRotation = Matrix4f(it.viewRotation),
				cameraX = it.cameraX,
				cameraY = it.cameraY,
				cameraZ = it.cameraZ,
			)
		}
		this.camera = snapshot
		scene = if (mainTarget.width > 0 && mainTarget.height > 0) {
			runtime.beginWorldPhase(
				normalInWorldFrame,
				DlssDimensions(mainTarget.width, mainTarget.height),
				snapshot,
			)
		} else {
			null
		}
		prepared = true
		return if (scene != null) runtime.activeJitter else null
	}

	/**
	 * Opens the world phase against Minecraft's real main target and returns the target the
	 * world must render into: the low-resolution scene target for an eligible DLSS frame, or
	 * [mainTarget] itself for a vanilla frame.
	 *
	 * Consumes a matching [prepare] when one exists, so a frame that went through the
	 * projection seam routes once rather than twice.
	 */
	fun begin(normalInWorldFrame: Boolean, mainTarget: RenderTarget): RenderTarget {
		if (!prepared || this.mainTarget !== mainTarget) {
			prepare(normalInWorldFrame, mainTarget)
		}
		isOpen = true

		val resolved = scene ?: mainTarget
		readout.worldPhase(
			mainTarget = mainTarget,
			scene = scene,
			frame = runtime.activeRoute?.frame,
			facts = SessionFacts(
				enabled = runtime.config.enabled,
				state = runtime.sessionState,
				qualityMode = runtime.qualityMode,
				renderPreset = runtime.renderPreset,
				outputDimensions = runtime.config.outputDimensions,
				renderDimensions = runtime.renderDimensions,
			),
			frameTimings = { runtime.frameEvaluation?.sampleTimings() },
			fgState = {
				// The monitor reads the plugin only while FG is active: with FG off the plugin's
				// presented count and fence are stale, and the line would report noise as news.
				if (runtime.frameGeneration.active) runtime.frameEvaluation?.sampleFgState() else null
			},
		)
		if (resolved !== lastResolved) {
			// SkyRenderer caches the target it was built against and reuses it every frame.
			lastResolved = resolved
			onWorldTargetChanged()
		}
		return resolved
	}

	/**
	 * Closes the world phase, restoring the vanilla main target for the rest of the frame, then
	 * presents an eligible frame's low-resolution scene into it.
	 */
	fun end() {
		if (!isOpen) {
			return
		}

		val rendered = scene
		val destination = mainTarget
		// Read before the phase closes: closing drops both, and the evaluation needs the same
		// jitter and motion the world was actually rendered with.
		val jitter = runtime.activeJitter
		val motion = runtime.activeMotion
		val camera = this.camera
		// The route and the velocity view behind it are captured while the phase is still open:
		// [terrainVelocityView] is non-null only inside an open velocity-MRT phase, which is
		// exactly the handoff the evaluation's motion-source gate needs.
		val route = runtime.motionVectorRoute
		val velocityView = terrainVelocityView
		isOpen = false
		prepared = false
		scene = null
		mainTarget = null
		this.camera = null

		// Evaluation decides whether this frame may become the predecessor for object motion.
		// Keep the runtime's published phase values alive through evaluation, then disposition
		// captures exactly once in finally: false/skipped evaluation and a throw reset without an
		// intermediate publish; only a composed DLSS result publishes.
		var completedDlssFrame = false
		try {
			if (rendered != null && destination != null) {
				completedDlssFrame = evaluate(rendered, destination, jitter, motion, route, velocityView, camera)
			}
		} finally {
			runtime.endWorldPhase(completedDlssFrame)
		}

		// Present only after the phase is closed, so the destination is the vanilla target.
		if (rendered != null && destination != null && !completedDlssFrame) {
			// No DLSS image reached the target, so the frame still has to show something: the
			// low-resolution scene, un-upscaled, exactly as it looked before composition existed.
			present(rendered, destination)
		}
	}

	/**
	 * Records this frame's DLSS work against the scene the world just rendered, and composes the
	 * upscaled result into [destination]. Returns true when the destination holds it.
	 *
	 * Recorded when the phase closes at the tail of `LevelRenderer.render`, after world geometry
	 * and the stress pass but before hand submission. Everything after this point (hand, screen
	 * effects, the 3D crosshair, HUD, and GUI) renders into the destination at output resolution
	 * on top of the DLSS image.
	 *
	 * A frame missing its jitter, its motion, or the handles behind its targets is skipped: it went
	 * through the phase without everything an evaluation needs, and DLSS reading a stale or absent
	 * input is worse than one frame of the low-resolution present.
	 */
	private fun evaluate(
		rendered: RenderTarget,
		destination: RenderTarget,
		jitter: DlssJitterOffset?,
		motion: DlssFrameMotion?,
		route: MotionVectorRoute,
		velocityView: GpuTextureView?,
		camera: DlssCameraSample?,
	): Boolean {
		if (jitter == null || motion == null) {
			return false
		}

		return evaluateFrame(rendered, destination, jitter, motion, route, velocityView, camera)
	}

	/**
	 * Breaks the accumulated history because the scene was replaced rather than because a frame
	 * was lost: a world load, a dimension change, or a disconnect.
	 *
	 * Called from the client thread outside the render loop, so it also drops any phase that was
	 * prepared and never rendered - the frame that prepared it belongs to the previous world and
	 * must not be closed against the new one.
	 */
	fun resetHistory() {
		discard()
		runtime.resetHistory()
	}

	override fun close() {
		discard()
		lastResolved = null
		runtime.close()
	}

	/**
	 * Drops a prepared or open phase without presenting it, and breaks the motion history the
	 * dropped frame would otherwise have left behind. The scene target itself stays owned by the
	 * runtime.
	 */
	private fun discard() {
		if (!isOpen && !prepared) {
			return
		}

		isOpen = false
		prepared = false
		scene = null
		mainTarget = null
		camera = null
		runtime.endWorldPhase()
		// This frame decided a route and moved the motion predecessor forward, but no image was
		// ever accumulated from it, so the next frame must start its history again.
		runtime.resetMotionHistory()
	}

	companion object {
		/** Production wiring: a real blit, a real sky-renderer reset, and the session readout. */
		@JvmStatic
		fun forMinecraft(runtime: RenderRuntime, readout: SessionReadout): WorldPhase {
			// The frame's camera travels as a lambda parameter: [end] captures the sample before
			// it clears the phase's field and passes the captured value through [evaluate] into
			// this lambda, so the production evaluation always reads the frame's own camera
			// rather than a field the close already nulled.
			return WorldPhase(
				runtime = runtime,
				present = ::blitSceneToMainTarget,
				onWorldTargetChanged = {
					Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.shouldResetSkyRenderer = true
				},
				readout = readout,
				evaluateFrame = { rendered, destination, jitter, motion, route, velocityView, camera ->
					val evaluation = runtime.frameEvaluation
					val resources = sceneResourcesOf(rendered)
					val destinationImage = (destination.colorTextureView as? VulkanGpuTextureView)
						?.texture()
						?.vkImage()
					if (evaluation == null || resources == null || destinationImage == null) {
						false
					} else {
						evaluation.evaluateFrame(
							resources,
							jitter,
							motion,
							destinationImage,
							route,
							velocityBindingOf(velocityView),
							camera,
						)
					}
				},
			)
		}

		/**
		 * Production resolution of one frame's DLSS-G inputs, read by the evaluation at world
		 * phase close: the main target as the output-sized HUD-less colour and the UI phase's
		 * held target as the output-sized UI colour+alpha, with the UI target's size checked
		 * against the main target's.
		 *
		 * The main target is the image the frame's SR output copy and the frame's UI composite
		 * both write into, so it is the frame's HUD-less colour until Present consumes it; the
		 * UI target is the frame's transparent overlay with its alpha. The world phase is
		 * closed by the time this runs, so the getters answer the vanilla main target and no
		 * override; the UI phase exists by then because the world phase's own initialization
		 * built it. A frame whose UI target does not exist yet - the first frame, or a resize
		 * frame whose held target is stale-sized against the recreated main target - resolves
		 * null and records SR-only, because a DLSS-G tag naming an image the frame is about to
		 * destroy is worse than one frame without FG. Returns null when either target is
		 * missing, mismatched in size, or not a Vulkan view.
		 */
		internal fun resolveFgInputs(): FgFrameInputs? {
			val main = Minecraft.getInstance().gameRenderer.mainRenderTarget() ?: return null
			val ui = ClientRuntime.active().activeUiPhase()?.uiTarget ?: return null
			if (ui.width != main.width || ui.height != main.height) {
				return null
			}
			val hudless = colorBindingOf(main) ?: return null
			val uiBinding = colorBindingOf(ui) ?: return null
			return FgFrameInputs(hudless, uiBinding)
		}

		/**
		 * Reads the `VkImage` / `VkImageView` handles and format out of a scene target.
		 *
		 * Every target the mod allocates is a [com.mojang.blaze3d.pipeline.TextureTarget] with
		 * colour and depth, so both views are present and both are Vulkan views - but the backend
		 * can be OpenGL, and a target can be destroyed between frames, so nothing here assumes it.
		 * A null result skips the frame's evaluation rather than crashing the render thread.
		 */
		private fun sceneResourcesOf(target: RenderTarget): SceneResources? {
			val color = colorBindingOf(target) ?: return null
			val depth = target.depthTextureView as? VulkanGpuTextureView ?: return null
			return SceneResources(
				color = color,
				depth = ImageBinding(
					view = depth.vkImageView(),
					image = depth.texture().vkImage(),
					format = VulkanConst.toVk(depth.texture().getFormat()),
				),
			)
		}

		/**
		 * Reads the colour `VkImage` / `VkImageView` handles and format out of a target's colour
		 * view, the way [sceneResourcesOf] reads the scene target's colour. Null for a
		 * non-Vulkan view (an OpenGL backend, or a test target).
		 */
		internal fun colorBindingOf(target: RenderTarget): ImageBinding? {
			val color = target.colorTextureView as? VulkanGpuTextureView ?: return null
			return ImageBinding(
				view = color.vkImageView(),
				image = color.texture().vkImage(),
				format = VulkanConst.toVk(color.texture().getFormat()),
			)
		}

		/**
		 * Reads the `VkImage` / `VkImageView` handles and format out of a scene-sized velocity
		 * view, the same way [sceneResourcesOf] reads the scene target.
		 *
		 * Null for a non-Vulkan view (an OpenGL backend, or a test view): the velocity-MRT route
		 * then hands no velocity binding to the evaluation, which skips the frame rather than
		 * evaluating against no motion source.
		 */
		private fun velocityBindingOf(view: GpuTextureView?): ImageBinding? {
			val vulkan = view as? VulkanGpuTextureView ?: return null
			return ImageBinding(
				view = vulkan.vkImageView(),
				image = vulkan.texture().vkImage(),
				format = VulkanConst.toVk(vulkan.texture().getFormat()),
			)
		}

		/**
		 * Draws the scene color over the whole main target with the full-screen blit pipeline.
		 *
		 * `TRACY_BLIT` is the un-blended sibling of `ENTITY_OUTLINE_BLIT`: same screen-quad and
		 * blit shaders, no blend function, so the world replaces the main target's cleared color
		 * instead of compositing over it. The sampler is NEAREST on purpose - no filtering means
		 * the presented image shows the render resolution exactly as DLSS will receive it.
		 */
		private fun blitSceneToMainTarget(scene: RenderTarget, main: RenderTarget) {
			val source = scene.colorTextureView ?: return
			val destination = main.colorTextureView ?: return

			RenderSystem.getDevice()
				.createCommandEncoder()
				.createRenderPass({ "DLSS scene present" }, destination, Optional.empty())
				.use { pass ->
					RenderSystem.bindDefaultUniforms(pass)
					pass.setPipeline(RenderPipelines.TRACY_BLIT)
					pass.bindTexture(
						"InSampler",
						source,
						RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST),
					)
					pass.draw(3, 1, 0, 0)
				}
		}
	}
}
