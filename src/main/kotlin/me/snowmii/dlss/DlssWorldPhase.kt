package me.snowmii.dlss

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.vulkan.VulkanConst
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import java.util.Optional

/**
 * Scopes one world render phase, which is the only window in which the mod's low-resolution
 * scene target stands in for Minecraft's main target.
 *
 * [DlssRenderRuntime] decides *whether* a frame is eligible and *what size* its target is.
 * This class decides *when* that target is visible to the renderer: the phase is opened at the
 * head of `LevelRenderer.render` and closed at its tail, and while it is open
 * [worldTargetOverride] is what `GameRenderer.mainRenderTarget()` answers. Everything outside
 * that window - post chains, GUI clear, screenshots, hand and item, screen effects, the 3D
 * crosshair, and presentation - keeps seeing the untouched full-size main target.
 *
 * Closing an eligible phase presents the scene color into the main target with a
 * nearest-neighbour full-screen blit. That blit is deliberately *not* an upscale: it is how the
 * low-resolution world becomes visible at all before DLSS evaluation exists, and its blockiness
 * is the point - it is the internal scene resolution, shown directly.
 *
 * Presentation and the sky-renderer reset are injected so the whole phase is verifiable off the
 * render thread; [forMinecraft] supplies the production wiring.
 */
class DlssWorldPhase(
	private val runtime: DlssRenderRuntime,
	private val present: (RenderTarget, RenderTarget) -> Unit,
	private val onWorldTargetChanged: () -> Unit,
	private val diagnostics: (String) -> Unit = {},
	private val probeResolvedTarget: (() -> RenderTarget?)? = null,
	/**
	 * Records this frame's DLSS work against the scene it just rendered. Injected because reaching
	 * the raw Vulkan handles behind a target needs Minecraft's backend types, and everything else
	 * here is verifiable off the render thread.
	 */
	private val evaluateFrame: (RenderTarget, DlssJitterOffset, DlssFrameMotion) -> Unit = { _, _, _ -> },
) : AutoCloseable {
	private var scene: RenderTarget? = null
	private var mainTarget: RenderTarget? = null
	private var prepared = false
	private var lastResolved: RenderTarget? = null
	private var reportedFirstDecision = false
	private var sampleStartedAt = 0L
	private var sampledFrames = 0

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
	 * Decides this frame's route and jitter without opening the phase, and returns the jitter
	 * an eligible DLSS frame must apply to its world projection, or null for a vanilla frame.
	 *
	 * Minecraft uploads the world projection in `GameRenderer.renderLevel`, before
	 * `LevelRenderer.render` runs, so the route has to be known earlier than the phase can be
	 * open - opening it that early would put hand, item, and screen effects behind the
	 * low-resolution override too. Splitting the decision from the window is what keeps both
	 * true, and the route is still decided exactly once per frame because [begin] consumes
	 * this preparation rather than repeating it.
	 *
	 * [camera] is the frame's camera as the projection seam sees it, and is what the runtime
	 * derives camera-only motion from. It is null only when the phase is opened without the
	 * projection seam having run, which publishes no motion for that frame.
	 */
	fun prepare(
		normalInWorldFrame: Boolean,
		mainTarget: RenderTarget,
		camera: DlssCameraSample? = null,
	): DlssJitterOffset? {
		// An exception thrown inside LevelRenderer.render skips the tail that closes the phase,
		// and a frame that prepared but never rendered leaves one unconsumed. Both are dropped
		// here rather than thrown on, because a stale phase must not turn one render failure
		// into a permanent one.
		discard()

		this.mainTarget = mainTarget
		scene = if (mainTarget.width > 0 && mainTarget.height > 0) {
			runtime.beginWorldPhase(
				normalInWorldFrame,
				DlssDimensions(mainTarget.width, mainTarget.height),
				camera,
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
		reportFirstDecision(mainTarget)
		sampleWorldFrameRate()
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
		isOpen = false
		prepared = false
		scene = null
		mainTarget = null
		runtime.endWorldPhase()

		// Present only after the phase is closed, so the destination is the vanilla target.
		if (rendered != null && destination != null) {
			evaluate(rendered, jitter, motion)
			present(rendered, destination)
		}
	}

	/**
	 * Records this frame's DLSS work against the scene the world just rendered.
	 *
	 * Recorded here, at the tail of the world phase, because that is the first moment the scene
	 * colour and depth are complete and the last moment before anything else in the frame touches
	 * them. A frame missing its jitter, its motion, or the handles behind its target is skipped:
	 * it went through the phase without everything an evaluation needs, and DLSS reading a stale
	 * or absent input is worse than one frame of the low-resolution present.
	 */
	private fun evaluate(rendered: RenderTarget, jitter: DlssJitterOffset?, motion: DlssFrameMotion?) {
		if (jitter == null || motion == null) {
			return
		}

		evaluateFrame(rendered, jitter, motion)
	}

	override fun close() {
		discard()
		lastResolved = null
		runtime.close()
	}

	/**
	 * Counts world phases and reports the rate every few seconds.
	 *
	 * This counts the world frames themselves, not Minecraft's own FPS counter, so the same
	 * number is comparable between a DLSS session and a `mc.dlss.enabled=false` session. Without
	 * it, "frame rate feels bad" cannot be separated from a dev client simply being slow.
	 */
	private fun sampleWorldFrameRate() {
		val now = System.nanoTime()
		if (sampleStartedAt == 0L) {
			sampleStartedAt = now
			return
		}

		sampledFrames++
		val elapsed = now - sampleStartedAt
		if (elapsed < SAMPLE_INTERVAL_NANOS) {
			return
		}

		val fps = sampledFrames * 1_000_000_000.0 / elapsed
		diagnostics(
			"DLSS world frame rate: %.1f fps over %d frames, route=%s, world=%s".format(
				fps,
				sampledFrames,
				runtime.activeRoute?.frame?.route ?: DlssFrameRoute.VANILLA,
				scene?.let { "${it.width}x${it.height}" } ?: "main-target",
			),
		)
		sampleStartedAt = now
		sampledFrames = 0
	}

	/**
	 * Reports the first world phase exactly once.
	 *
	 * Without this, an engaged DLSS route and a session that never started look identical from
	 * outside: both render a normal-looking frame and log nothing. The line names the measured
	 * main target, the route actually taken, the session's own reason for it, and the render
	 * dimensions, which is enough to tell those two apart from the log alone.
	 */
	private fun reportFirstDecision(mainTarget: RenderTarget) {
		if (reportedFirstDecision) {
			return
		}

		reportedFirstDecision = true
		val frame = runtime.activeRoute?.frame
		// What the renderer actually resolves mid-phase. If this is not the scene target, the
		// route decided correctly but the redirect never reached the frame graph.
		val resolved = probeResolvedTarget?.invoke()
		diagnostics(
			"DLSS first world phase: main=${mainTarget.width}x${mainTarget.height}" +
				" route=${frame?.route ?: DlssFrameRoute.VANILLA}" +
				" reason=${frame?.reason ?: "startup-unavailable"}" +
				" render=${runtime.renderDimensions ?: "none"}" +
				" scene=${scene?.let { "${it.width}x${it.height}" } ?: "none"}" +
				" resolved=${resolved?.let { "${it.width}x${it.height}" } ?: "unprobed"}" +
				" redirected=${resolved != null && resolved === scene}",
		)
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
		runtime.endWorldPhase()
		// This frame decided a route and moved the motion predecessor forward, but no image was
		// ever accumulated from it, so the next frame must start its history again.
		runtime.resetMotionHistory()
	}

	companion object {
		private const val SAMPLE_INTERVAL_NANOS = 5_000_000_000L

		/** Production wiring: a real blit and a real sky-renderer reset. */
		@JvmStatic
		fun forMinecraft(runtime: DlssRenderRuntime, diagnostics: (String) -> Unit): DlssWorldPhase = DlssWorldPhase(
			runtime = runtime,
			present = ::blitSceneToMainTarget,
			onWorldTargetChanged = {
				Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.shouldResetSkyRenderer = true
			},
			diagnostics = diagnostics,
			probeResolvedTarget = { Minecraft.getInstance().gameRenderer.mainRenderTarget() },
			evaluateFrame = { rendered, jitter, motion ->
				val evaluation = runtime.frameEvaluation
				val resources = sceneResourcesOf(rendered)
				if (evaluation != null && resources != null) {
					evaluation.evaluateFrame(resources, jitter, motion)
				}
			},
		)

		/**
		 * Reads the `VkImage` / `VkImageView` handles and formats out of a scene target.
		 *
		 * Every target the mod allocates is a [com.mojang.blaze3d.pipeline.TextureTarget] with
		 * colour and depth, so both views are present and both are Vulkan views - but the backend
		 * can be OpenGL, and a target can be destroyed between frames, so nothing here assumes it.
		 * A null result skips the frame's evaluation rather than crashing the render thread.
		 */
		private fun sceneResourcesOf(target: RenderTarget): DlssSceneResources? {
			val color = target.colorTextureView as? VulkanGpuTextureView ?: return null
			val depth = target.depthTextureView as? VulkanGpuTextureView ?: return null
			return DlssSceneResources(
				colorView = color.vkImageView(),
				colorImage = color.texture().vkImage(),
				colorFormat = VulkanConst.toVk(color.texture().getFormat()),
				depthView = depth.vkImageView(),
				depthImage = depth.texture().vkImage(),
				depthFormat = VulkanConst.toVk(depth.texture().getFormat()),
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
