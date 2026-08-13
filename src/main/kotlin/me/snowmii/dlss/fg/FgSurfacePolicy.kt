package me.snowmii.dlss.fg

/**
 * The FG-mode policy Minecraft's swapchain seams read.
 *
 * DLSS-G needs a swapchain that is not FIFO-presented and whose image count covers its declared
 * back buffers, and it needs that swapchain recreated every time FG switches on or off -
 * Streamline's guide forbids driving FG on a swapchain that was created under the other policy.
 * Minecraft already owns the recreation path ([net.minecraft.client.Minecraft.invalidateSurfaceConfiguration]
 * making the next [net.minecraft.client.Minecraft.renderFrame] reconfigure the surface), so this
 * policy is only the state those seams read, held off the render thread:
 *
 * - a mode transition invalidates the surface configuration exactly once - the transition itself
 *   runs the injected invalidation, and a call that changes nothing runs nothing;
 * - while FG is active the reconfigure path reads vsync as false, which selects a non-FIFO
 *   present mode, without ever touching the stored option - the stored value is read by value
 *   and returned untouched;
 * - while FG is active the swapchain minimum image count is raised to at least the declared
 *   DLSS-G back buffers, never lowered below what Minecraft would create.
 *
 * The declared back-buffer count defaults to the number the mod records with
 * [me.snowmii.dlss.session.LifecycleAdapter.configureFg]; a policy for a different declaration
 * is constructed explicitly.
 */
class FgSurfacePolicy(
	private val declaredBackBuffers: Int = DEFAULT_DECLARED_BACK_BUFFERS,
	private val invalidateSurfaceConfiguration: () -> Unit = {},
) {
	private var frameGenerationActive = false

	/** Whether FG is switched on right now. */
	val active: Boolean
		get() = frameGenerationActive

	/**
	 * Switches FG on or off and reports whether the mode changed.
	 *
	 * A real transition invalidates Minecraft's surface configuration exactly once, so the next
	 * frame recreates the swapchain under the new policy; a call that leaves the mode where it
	 * was is a no-op that invalidates nothing.
	 */
	fun setFrameGenerationActive(active: Boolean): Boolean {
		if (active == frameGenerationActive) {
			return false
		}
		frameGenerationActive = active
		invalidateSurfaceConfiguration()
		return true
	}

	/**
	 * The vsync value the reconfigure path must read: false while FG is active, so
	 * `getSupportedVsyncMode` selects a non-FIFO present mode, and the stored option's own
	 * value otherwise. The stored option is never written.
	 */
	fun effectiveVsyncEnabled(stored: Boolean): Boolean =
		if (frameGenerationActive) false else stored

	/**
	 * The minimum image count the swapchain must be created with: the declared DLSS-G back
	 * buffers while FG is active, and Minecraft's own count otherwise. Never lowers the
	 * count below what Minecraft would create.
	 */
	fun minImageCount(vanilla: Int): Int =
		if (frameGenerationActive) maxOf(vanilla, declaredBackBuffers) else vanilla

	companion object {
		/**
		 * The DLSS-G back-buffer count the mod declares when recording FG options:
		 * `numBackBuffers = 3` in the recorded `DLSSGOptions`.
		 */
		const val DEFAULT_DECLARED_BACK_BUFFERS = 3
	}
}
