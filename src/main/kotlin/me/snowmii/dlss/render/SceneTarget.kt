package me.snowmii.dlss.render
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.session.DlssFrameRoute

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget

/**
 * Owns the mod's low-resolution world scene target.
 *
 * Minecraft 26.2 has no render-scale API: `GameRenderer.mainRenderTarget()` is built at
 * window size and is used by the world frame graph, post chains, screenshots, the GUI, and
 * presentation. Rendering the world lower-resolution therefore needs a *companion* target
 * rather than a resize of the vanilla one, which is exactly the invariant this class holds:
 * the main target is never touched here.
 *
 * Allocation is driven entirely by [WorldTargetRoute]. A DLSS route gets a target sized to
 * the NGX-queried render dimensions, reused for as long as those dimensions hold. Any vanilla
 * route releases it, so a session that latches fallback stops paying for scene memory.
 *
 * Allocation and release are injected so the lifecycle is verifiable off the render thread;
 * [forMinecraft] supplies the production pair.
 */
class SceneTarget(
	private val allocate: (Int, Int) -> RenderTarget,
	private val release: (RenderTarget) -> Unit,
) : AutoCloseable {
	private var target: RenderTarget? = null
	private var dimensions: DlssDimensions? = null

	/** The currently held scene target, or null when the last route was vanilla. */
	val current: RenderTarget?
		get() = target

	/** Dimensions the held target was allocated at, or null when nothing is held. */
	val currentDimensions: DlssDimensions?
		get() = dimensions

	/**
	 * Returns the scene target the world phase should render into, or null when the frame
	 * must use the vanilla main target at output resolution.
	 */
	fun acquire(route: WorldTargetRoute): RenderTarget? {
		if (route.frame.route != DlssFrameRoute.DLSS) {
			releaseCurrent()
			return null
		}

		val wanted = route.worldDimensions
		val existing = target
		if (existing != null && dimensions == wanted) {
			return existing
		}

		releaseCurrent()
		return allocate(wanted.width, wanted.height).also {
			target = it
			dimensions = wanted
		}
	}

	override fun close() = releaseCurrent()

	private fun releaseCurrent() {
		target?.let(release)
		target = null
		dimensions = null
	}

	companion object {
		const val LABEL = "DLSS Scene"

		/**
		 * Production seam. [TextureTarget] allocates color plus depth with vanilla's usage
		 * flags and asserts the render thread, matching how Minecraft builds its own
		 * screen-sized companion targets in `LevelRenderer.render`.
		 */
		@JvmStatic
		fun forMinecraft(): SceneTarget = SceneTarget(
			allocate = { width, height -> TextureTarget(LABEL, width, height, true, GpuFormat.RGBA8_UNORM) },
			release = RenderTarget::destroyBuffers,
		)
	}
}
