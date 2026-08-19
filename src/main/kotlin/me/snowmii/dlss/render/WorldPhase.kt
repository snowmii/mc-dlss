package me.snowmii.dlss.render
import me.snowmii.streamline.ImageBinding
import me.snowmii.dlss.client.ClientRuntime
import me.snowmii.dlss.readout.FramePacingProbe
import me.snowmii.dlss.readout.SessionFacts
import me.snowmii.dlss.readout.SessionReadout
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.StreamlineSession
import me.snowmii.dlss.render.mrt.MotionVectorPipeline
import me.snowmii.dlss.render.mrt.MotionVectorRoute
import net.minecraft.client.renderer.entity.state.EntityRenderState
import org.joml.Matrix4f
import org.joml.Vector3f
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
 * The evaluate-and-compose seam a phase closes through: rendered scene, destination, this frame's
 * jitter and motion, the session's world-motion route, the scene velocity companion view behind
 * it, and the camera the projection seam sampled. Answers true when the destination holds the
 * upscaled frame.
 */
typealias EvaluateFrame = (
	RenderTarget,
	RenderTarget,
	DlssJitterOffset,
	DlssFrameMotion,
	MotionVectorRoute,
	GpuTextureView?,
	DlssCameraSample?,
) -> Boolean

/**
 * Only window where the low-resolution scene target stands in for Minecraft's main target.
 *
 * [RenderRuntime] decides eligibility and size. This class decides *when* that target is
 * visible: open at `LevelRenderer.render` head, close at its tail; while open,
 * [worldTargetOverride] is what `GameRenderer.mainRenderTarget()` answers. World scene and
 * stress pass land in the low-res target; hand/item, screen effects, crosshair, post, GUI,
 * screenshots, and present run after evaluation against the full-size main target.
 *
 * Close evaluates DLSS into the main target. Failed evaluation: nearest-neighbour blit of the
 * low-res scene, not an upscale — a failed frame is visibly internal resolution, not black.
 *
 * [present] and [onWorldTargetChanged] are injected so the phase is testable off the render
 * thread; [forMinecraft] is production wiring.
 */
class WorldPhase(
	private val runtime: RenderRuntime,
	private val present: (RenderTarget, RenderTarget) -> Unit,
	private val onWorldTargetChanged: () -> Unit,
	/**
	 * Injected: Vulkan handles need Minecraft backend types; everything else here is testable
	 * off the render thread. Camera is a parameter because [end] clears the field before
	 * production wiring would read it.
	 */
	private val evaluateFrame: EvaluateFrame = { _, _, _, _, _, _, _ -> false },
	private val readout: SessionReadout = SessionReadout.NOOP,
) : AutoCloseable {
	private var sceneTarget: RenderTarget? = null
	private var mainRenderTarget: RenderTarget? = null
	private var projectionPrepared = false
	private var lastResolvedTarget: RenderTarget? = null
	/** Snapshotted at [prepare]; Minecraft reuses the seam's live matrices across frames. */
	private var cameraSample: DlssCameraSample? = null

	var isOpen: Boolean = false
		private set

	/**
	 * Target `GameRenderer.mainRenderTarget()` must answer with, or null when the caller gets
	 * the vanilla main target. Non-null only inside an eligible DLSS world phase.
	 */
	val worldTargetOverride: RenderTarget?
		get() = if (isOpen) sceneTarget else null

	fun presentStart(): Boolean {
		runtime.pacing.begin(FramePacingProbe.Span.QUEUE_PRESENT)
		return runtime.frameEvaluation?.presentStart() == true
	}

	fun presentEnd(): Boolean {
		val emitted = runtime.frameEvaluation?.presentEnd() == true
		runtime.pacing.end(FramePacingProbe.Span.QUEUE_PRESENT)
		return emitted
	}

	// Measured every frame: a blocking acquire is the pacer holding images. Compare with FG off.
	fun acquireStart() = runtime.pacing.begin(FramePacingProbe.Span.SWAPCHAIN_ACQUIRE)
	fun acquireEnd() = runtime.pacing.end(FramePacingProbe.Span.SWAPCHAIN_ACQUIRE)

	fun submitStart() = runtime.pacing.begin(FramePacingProbe.Span.RENDER_SUBMIT)
	fun submitEnd() = runtime.pacing.end(FramePacingProbe.Span.RENDER_SUBMIT)

	// Mixins call the active world phase (null before DLSS starts). Markers are values, so a
	// new seam is an enum constant. Input/simulation fire before LevelRenderer.render; the
	// adapter gates on READY, not on the phase being open.
	fun reflexInputSample(): Boolean {
		// slReflexSleep runs before the marker emits. Bracket here so the span covers the
		// sleep even when the marker is refused.
		runtime.pacing.begin(FramePacingProbe.Span.REFLEX_SLEEP)
		try {
			return runtime.frameEvaluation?.reflexInputSample() == true
		} finally {
			runtime.pacing.end(FramePacingProbe.Span.REFLEX_SLEEP)
		}
	}
	fun reflexMarker(type: StreamlineSession.ReflexMarkerType): Boolean =
		runtime.frameEvaluation?.reflexMarker(type) == true

	/**
	 * Observes a pipeline whose ownership can change the session's world-motion route.
	 * Only while [isOpen]: shader reload, GUI, post, and present pipelines must not latch.
	 *
	 * Two callers: Vulkan lazy-compile (backstop) and terrain `renderGroup` HEAD (classifies
	 * before pass shape), so a first foreign pipeline keeps vanilla passthrough.
	 */
	fun observePipeline(pipeline: MotionVectorPipeline) {
		if (isOpen) {
			runtime.observeWorldPipeline(pipeline)
		}
	}

	/**
	 * Not gated on [isOpen]: `extractVisibleEntities` runs before [begin]. Only a completed
	 * DLSS frame publishes; vanilla/abandoned/replaced-world/release/close reset instead.
	 */
	fun captureEntity(id: Int, x: Double, y: Double, z: Double) {
		runtime.captureEntity(id, x, y, z)
	}

	/** Records the returned render-state identity alongside its stable entity id and pose. */
	fun captureEntity(state: EntityRenderState, id: Int, x: Double, y: Double, z: Double) {
		runtime.captureEntity(state, id, x, y, z)
	}

	/**
	 * Same [isOpen] rule as [captureEntity]. Key is a long in the moving-block domain, so an
	 * entity id with the same numeric value cannot share this slot.
	 */
	fun captureBlock(id: Long, x: Double, y: Double, z: Double) {
		runtime.captureBlock(id, x, y, z)
	}

	fun entityId(state: EntityRenderState): Int? = if (isOpen) runtime.entityId(state) else null

	/** Entity-model draws may bind the scene velocity attachment. */
	val entityVelocityActive: Boolean
		get() = isOpen && runtime.motionVectorRoute == MotionVectorRoute.VELOCITY_MRT && terrainVelocityView != null

	val activeJitter: DlssJitterOffset?
		get() = if (isOpen) runtime.activeJitter else null

	val currentViewProjection: Matrix4f?
		get() = if (isOpen) runtime.currentViewProjection else null

	fun objectMotionDisplacement(entityId: Int): Vector3f? =
		if (entityVelocityActive) runtime.objectMotion.objectDisplacement(entityId) else null

	/**
	 * Same velocity-active gate as [objectMotionDisplacement]; resolved in the moving-block
	 * (long-key) domain of the shared history.
	 */
	fun blockMotionDisplacement(blockId: Long): Vector3f? =
		if (entityVelocityActive) runtime.objectMotion.objectDisplacement(blockId) else null

	/**
	 * Scene-sized RG16_FLOAT velocity view terrain `renderGroup` may attach, or null to stay
	 * vanilla. Non-null only while open on VELOCITY_MRT with a companion. Plain field/enum
	 * reads — the vanilla path cannot throw.
	 */
	val terrainVelocityView: GpuTextureView?
		get() = if (isOpen && runtime.motionVectorRoute == MotionVectorRoute.VELOCITY_MRT) {
			runtime.activeVelocityView
		} else {
			null
		}

	/**
	 * Depth of the target the world actually rendered into, for Fabulous cloud depth-test.
	 * Clouds' own target depth does not contain terrain in front of them. An eligible DLSS
	 * frame renders into [sceneTarget]; reading vanilla main would miss (full-res vs scene)
	 * or test a buffer that never received terrain.
	 */
	val sceneDepthView: GpuTextureView?
		get() = if (isOpen) (sceneTarget ?: mainRenderTarget)?.getDepthTextureView() else null

	/**
	 * Published camera motion while open. Stress pass reads this at the tail of the world
	 * phase, before [end] consumes it, so the velocity buffer and the DLSS evaluation share
	 * the same jitter-stripped reprojection.
	 */
	val activeMotion: DlssFrameMotion?
		get() = if (isOpen) runtime.activeMotion else null

	/**
	 * Decides route and jitter without opening the phase. Minecraft uploads the world
	 * projection in `GameRenderer.renderLevel` before `LevelRenderer.render`, so the route
	 * must be known before the override window exists. [begin] consumes this rather than
	 * repeating it.
	 *
	 * Snapshots [camera] matrices: Minecraft reuses the seam's live ones across frames.
	 * Null camera (projection seam never ran) publishes no motion for that frame.
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
		discardUnfinishedFrame()

		this.mainRenderTarget = mainTarget
		val snapshot = camera?.let {
			DlssCameraSample(
				projection = Matrix4f(it.projection),
				viewRotation = Matrix4f(it.viewRotation),
				cameraX = it.cameraX,
				cameraY = it.cameraY,
				cameraZ = it.cameraZ,
			)
		}
		this.cameraSample = snapshot
		sceneTarget = if (mainTarget.width > 0 && mainTarget.height > 0) {
			runtime.beginWorldPhase(
				normalInWorldFrame,
				Dimensions(mainTarget.width, mainTarget.height),
				snapshot,
			)
		} else {
			null
		}
		projectionPrepared = true
		return if (sceneTarget != null) runtime.activeJitter else null
	}

	/**
	 * Opens the phase and returns the target the world must render into. Consumes a matching
	 * [prepare] so a projection-seam frame routes once, not twice.
	 */
	fun begin(normalInWorldFrame: Boolean, mainTarget: RenderTarget): RenderTarget {
		if (!projectionPrepared || this.mainRenderTarget !== mainTarget) {
			prepare(normalInWorldFrame, mainTarget)
		}
		isOpen = true

		val resolved = sceneTarget ?: mainTarget
		readout.reportWorldPhase(
			mainTarget = mainTarget,
			scene = sceneTarget,
			frame = runtime.worldTargetRoute?.frame,
			facts = SessionFacts(
				enabled = runtime.config.enabled,
				state = runtime.sessionState,
				qualityMode = runtime.qualityMode,
				renderPreset = runtime.renderPreset,
				outputDimensions = runtime.outputDimensions,
				renderDimensions = runtime.dlssRenderDimensions,
			),
			frameTimings = { runtime.frameEvaluation?.sampleTimings() },
			pacing = { runtime.pacing.sampleAndReset() },
			fgState = {
				// The monitor reads the plugin only while FG is active: with FG off the plugin's
				// presented count and fence are stale, and the line would report noise as news.
				if (runtime.frameGeneration.effective) runtime.frameEvaluation?.sampleFgState() else null
			},
		)
		if (resolved !== lastResolvedTarget) {
			// SkyRenderer caches the target it was built against and reuses it every frame.
			lastResolvedTarget = resolved
			onWorldTargetChanged()
		}
		return resolved
	}

	fun end() {
		if (!isOpen) {
			return
		}

		val rendered = sceneTarget
		val destination = mainRenderTarget
		// Read before the phase closes: closing drops both, and the evaluation needs the same
		// jitter and motion the world was actually rendered with.
		val jitter = runtime.activeJitter
		val motion = runtime.activeMotion
		val camera = this.cameraSample
		// The route and the velocity view behind it are captured while the phase is still open:
		// [terrainVelocityView] is non-null only inside an open velocity-MRT phase, which is
		// exactly the handoff the evaluation's motion-source gate needs.
		val route = runtime.motionVectorRoute
		val velocityView = terrainVelocityView
		isOpen = false
		projectionPrepared = false
		sceneTarget = null
		mainRenderTarget = null
		this.cameraSample = null

		// Evaluation decides whether this frame may become the predecessor for object motion.
		// Keep the runtime's published phase values alive through evaluation, then disposition
		// captures exactly once in finally: false/skipped evaluation and a throw reset without an
		// intermediate publish; only a composed DLSS result publishes.
		var completedDlssFrame = false
		try {
			if (rendered != null && destination != null) {
				completedDlssFrame = evaluateAndCompose(rendered, destination, jitter, motion, route, velocityView, camera)
			}
		} finally {
			runtime.endWorldPhase(completedDlssFrame)
		}

		// Present only after close, so the destination is the vanilla target.
		if (rendered != null && destination != null && !completedDlssFrame) {
			// Failed evaluation: nearest-neighbour blit, not an upscale.
			present(rendered, destination)
		}
	}

	/**
	 * After world geometry and the stress pass, before hand submission. Missing jitter, motion,
	 * or handles: skip. DLSS reading a stale/absent input is worse than one low-res present.
	 */
	private fun evaluateAndCompose(
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
	 * Scene replaced (world load, dimension change, disconnect), not a lost frame. Client
	 * thread, outside the render loop: also drops a prepared-never-rendered phase belonging
	 * to the previous world.
	 */
	fun resetHistory() {
		discardUnfinishedFrame()
		runtime.resetHistory()
	}

	override fun close() {
		discardUnfinishedFrame()
		lastResolvedTarget = null
		runtime.close()
	}

	/**
	 * Drops a prepared or open phase without presenting. Scene target stays with the runtime.
	 */
	private fun discardUnfinishedFrame() {
		if (!isOpen && !projectionPrepared) {
			return
		}

		isOpen = false
		projectionPrepared = false
		sceneTarget = null
		mainRenderTarget = null
		cameraSample = null
		runtime.endWorldPhase()
		// This frame decided a route and moved the motion predecessor forward, but no image was
		// ever accumulated from it, so the next frame must start its history again.
		runtime.resetMotionHistory()
	}

	companion object {
		@JvmStatic
		fun forMinecraft(runtime: RenderRuntime, readout: SessionReadout): WorldPhase {
			// [end] clears cameraSample before this lambda runs; the captured parameter is
			// this frame's camera, not the nulled field.
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
		 * Main target = HUD-less colour (SR copy and UI composite both write it; Present
		 * consumes it). UI target = transparent overlay. Runs after world-phase close, so
		 * getters answer vanilla main. Null if either target is missing, size-mismatched, or
		 * not Vulkan: tagging an image the frame is about to destroy is worse than one
		 * SR-only frame.
		 */
		internal fun resolveFrameGenerationInputs(): FgFrameInputs? {
			val main = Minecraft.getInstance().gameRenderer.mainRenderTarget()
			val ui = ClientRuntime.active().activeUiPhase()?.uiTarget ?: return null
			if (ui.width != main.width || ui.height != main.height) {
				return null
			}
			val hudless = colorBindingOf(main) ?: return null
			val uiBinding = colorBindingOf(ui) ?: return null
			return FgFrameInputs(hudless, uiBinding)
		}

		/**
		 * Null skips evaluation rather than crashing: backend can be OpenGL, and a target can
		 * be destroyed between frames.
		 */
		private fun sceneResourcesOf(target: RenderTarget): SceneResources? {
			val color = colorBindingOf(target) ?: return null
			val depth = target.depthTextureView as? VulkanGpuTextureView ?: return null
			return SceneResources(
				color = color,
				depth = ImageBinding(
					depth.vkImageView(),
					depth.texture().vkImage(),
					VulkanConst.toVk(depth.texture().format),
				),
			)
		}

		/** Null for a non-Vulkan view (OpenGL backend, or a test target). */
		internal fun colorBindingOf(target: RenderTarget): ImageBinding? {
			val color = target.colorTextureView as? VulkanGpuTextureView ?: return null
			return ImageBinding(
				color.vkImageView(),
				color.texture().vkImage(),
				VulkanConst.toVk(color.texture().format),
			)
		}

		/**
		 * Null for a non-Vulkan view: evaluation then skips rather than running with no
		 * motion source.
		 */
		private fun velocityBindingOf(view: GpuTextureView?): ImageBinding? {
			val vulkan = view as? VulkanGpuTextureView ?: return null
			return ImageBinding(
				vulkan.vkImageView(),
				vulkan.texture().vkImage(),
				VulkanConst.toVk(vulkan.texture().format),
			)
		}

		/**
		 * Unblended full-screen blit (`TRACY_BLIT`). NEAREST on purpose: presented image is
		 * exactly the render resolution DLSS receives.
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
