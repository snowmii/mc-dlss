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
	/**
	 * Allocates the scene-sized velocity companion at [width]x[height], or returns null for a
	 * runtime that does not render a velocity attachment (the default keeps every existing
	 * caller compiling and holding no companion).
	 */
	private val allocateVelocity: (Int, Int) -> RenderTarget? = { _, _ -> null },
) : AutoCloseable {
	private var target: RenderTarget? = null
	private var velocity: RenderTarget? = null
	private var dimensions: DlssDimensions? = null

	/** The currently held scene target, or null when the last route was vanilla. */
	val current: RenderTarget?
		get() = target

	/**
	 * The scene-sized velocity companion, or null when nothing is held or the route allocates none.
	 *
	 * The companion lives and dies with the scene target: same dimensions, same allocation
	 * event, same release, so the two can never drift apart across a resize or a fallback.
	 */
	val currentVelocity: RenderTarget?
		get() = velocity

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
		val allocated = allocate(wanted.width, wanted.height)
		// The scene half of the pair is allocated, but the pair is not published until the
		// velocity companion exists too. If companion allocation fails, release the scene half
		// exactly once and rethrow: every field stays null, so releaseCurrent/close cannot
		// release it again and the next acquire starts from a clean slate.
		val velocityAllocated = try {
			allocateVelocity(wanted.width, wanted.height)
		} catch (t: Throwable) {
			release(allocated)
			throw t
		}
		target = allocated
		velocity = velocityAllocated
		dimensions = wanted
		return allocated
	}

	override fun close() = releaseCurrent()

	private fun releaseCurrent() {
		target?.let(release)
		velocity?.let(release)
		target = null
		velocity = null
		dimensions = null
	}

	companion object {
		const val LABEL = "DLSS Scene"

		/** The scene-sized RG16_FLOAT payload format the velocity attachment must carry. */
		val VELOCITY_FORMAT = GpuFormat.RG16_FLOAT

		/**
		 * Production seam. [TextureTarget] allocates color plus depth with vanilla's usage
		 * flags and asserts the render thread, matching how Minecraft builds its own
		 * screen-sized companion targets in `LevelRenderer.render`. The velocity companion is a
		 * depthless [TextureTarget] at the same render dimensions in RG16_FLOAT, so its color
		 * view is directly bindable as the color-1 attachment of the terrain passes.
		 */
		@JvmStatic
		fun forMinecraft(): SceneTarget = SceneTarget(
			allocate = { width, height -> TextureTarget(LABEL, width, height, true, GpuFormat.RGBA8_UNORM) },
			release = RenderTarget::destroyBuffers,
			allocateVelocity = { width, height -> TextureTarget("$LABEL Velocity", width, height, false, VELOCITY_FORMAT) },
		)
	}
}
