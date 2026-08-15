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
 * A third axis, frame support, suspends the *effective* mode without touching the user's mode
 * or the latch: unsupported frames (pause, loading, menu, panorama, resize, fullscreen
 * transition) flip [active] off through [setFrameSupported], the composition seam then records
 * SR-only frames, and the first supported frame flips it back on - a user-off or latched policy
 * stays off through the whole cycle. The swapchain reads ([effectiveVsyncEnabled],
 * [minImageCount]) deliberately follow the user's mode, not the effective one: a suspension is
 * transient, and recreating the swapchain FIFO on every pause and back on every resume would
 * leave FG running on exactly the swapchain the guide forbids.
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
	private var latchedOff = false
	private var frameSupported = true

	/**
	 * Whether FG is effective right now: the user's mode AND a frame FG may compose on.
	 *
	 * Suspended frames read false here while [frameGenerationActive] keeps the user's mode,
	 * so a supported frame resumes exactly what the user's mode says - and a user-off or
	 * latched policy reads false through the whole cycle.
	 */
	val active: Boolean
		get() = frameGenerationActive && frameSupported

	/**
	 * Whether a non-OK DLSS-G status latched FG off for the session.
	 *
	 * A latched policy is off for good: every later re-arm is refused, the vsync and
	 * image-count reads answer the stored/vanilla values, and only a fresh session's new
	 * policy instance can run FG again.
	 */
	val latched: Boolean
		get() = latchedOff

	/**
	 * Switches FG on or off and reports whether the mode changed.
	 *
	 * A real transition invalidates Minecraft's surface configuration exactly once, so the next
	 * frame recreates the swapchain under the new policy; a call that leaves the mode where it
	 * was is a no-op that invalidates nothing. A re-arm of a latched policy is refused before
	 * anything else: the latch is the plugin's own failure verdict for the session, and no
	 * toggle may overturn it.
	 */
	fun setFrameGenerationActive(active: Boolean): Boolean {
		if (latchedOff && active) {
			return false
		}
		if (active == frameGenerationActive) {
			return false
		}
		frameGenerationActive = active
		invalidateSurfaceConfiguration()
		return true
	}

	/**
	 * Marks whether the current frame may compose FG, and reports whether the effective mode
	 * changed.
	 *
	 * A supported-to-unsupported flip suspends [active] off while the user's mode and the latch
	 * stay untouched, so the caller can attach its one eOff options record to exactly this
	 * transition; the frames in between change nothing, and a supported frame resumes. The
	 * return answers true only when the user's mode is on - suspending an already-off policy
	 * changes nothing, and so does resuming a user-off or latched one, which is the user-off
	 * and session-latch precedence. Deliberately no surface invalidation: see the class
	 * comment, the swapchain policy follows the user's mode across a suspension.
	 */
	fun setFrameSupported(supported: Boolean): Boolean {
		if (supported == frameSupported) {
			return false
		}
		frameSupported = supported
		return frameGenerationActive
	}

	/**
	 * Latches FG off for the session: records the latch, switches the mode off - invalidating
	 * the surface configuration exactly once when the mode actually changes, so the next
	 * frame recreates the swapchain with vsync and image count restored - and refuses every
	 * later re-arm. Returns whether this call was the one that latched; a repeat call answers
	 * false so its caller can keep a one-shot side effect (the eOff options record, the exact
	 * diagnostic) attached to the first latch.
	 */
	fun latchOff(): Boolean {
		if (latchedOff) {
			return false
		}
		latchedOff = true
		if (frameGenerationActive) {
			frameGenerationActive = false
			invalidateSurfaceConfiguration()
		}
		return true
	}

	/**
	 * The vsync value the reconfigure path must read: false while the user's FG mode is on, so
	 * `getSupportedVsyncMode` selects a non-FIFO present mode, and the stored option's own
	 * value otherwise. The stored option is never written. Follows the user's mode rather than
	 * the effective one so a transient frame-support suspension never recreates a FIFO
	 * swapchain FG would then resume on (see the class comment).
	 */
	fun effectiveVsyncEnabled(stored: Boolean): Boolean =
		if (frameGenerationActive) false else stored

	/**
	 * The minimum image count the swapchain must be created with: the declared DLSS-G back
	 * buffers while the user's FG mode is on, and Minecraft's own count otherwise - the user
	 * mode, not the effective one, for the same reason as [effectiveVsyncEnabled]. Never
	 * lowers the count below what Minecraft would create.
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
