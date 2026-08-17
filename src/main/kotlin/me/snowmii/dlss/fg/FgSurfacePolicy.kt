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
 * A second axis, frame support, suspends the *effective* mode without touching the user's
 * mode: unsupported frames (pause, loading, menu, panorama, resize, fullscreen transition, and
 * an unhealthy DLSS-G status) flip [effective] off through [setCompositionSupported], the composition
 * seam then records SR-only frames, and the first supported frame flips it back on - a
 * user-off policy stays off through the whole cycle. The swapchain reads
 * ([effectiveVsyncEnabled], [minImageCount]) deliberately follow the user's mode, not the
 * effective one: a suspension is transient, and recreating the swapchain FIFO on every pause
 * and back on every resume would leave FG running on exactly the swapchain the guide forbids.
 *
 * There is deliberately no session latch. An earlier design let the first non-OK
 * `slDLSSGGetState` status end frame generation for the session, which also restored FIFO
 * vsync for good; but the status word is a bitmask about *this frame* - a quality-mode change
 * produces one for a frame or two by construction - so a transient bit killed FG with no way
 * back short of a restart. Every "FG must stop" condition is a suspension on the frame-support
 * axis instead, and every one of them is reversible.
 *
 * The declared back-buffer count is derived from [numFramesToGenerate], not fixed: it is the
 * number [me.snowmii.dlss.session.LifecycleAdapter.configureFg] records AND the swapchain
 * minimum, and both have to grow with the multiplier (see [backBuffersFor]).
 */
class FgSurfacePolicy(
	private val invalidateSurfaceConfiguration: () -> Unit = {},
) {
	private var frameGenerationActive = false
	private var frameSupported = true

	/**
	 * Re-records the DLSS-G options in the eOff mode, run by this policy on every transition that
	 * takes [effective] from true to false - the user toggle, the status suspension, and the
	 * image-release suspension alike.
	 *
	 * The record used to sit at the three call sites that drove those transitions, each pairing a
	 * mutator call with its own `if (...) recordFgModeOff()`. A fourth transition added anywhere
	 * else silently skipped the record, and the plugin kept interpolating on released images -
	 * the failure the release path is annotated for. The transitions all run through this class,
	 * so the record belongs to them rather than to their callers.
	 *
	 * Bound by [me.snowmii.dlss.render.RenderRuntime] to its session bridge, which is what pairs
	 * a policy with the session whose options it records; a policy nothing has wired reads as the
	 * same "no session to record against" the null bridge always gave.
	 */
	var recordFrameGenerationOff: () -> Unit = {}

	/**
	 * The DLSS-G multiplier in effect in `numFramesToGenerate` units: 1 = 2x, 2 = 3x, and so on.
	 *
	 * Written by [me.snowmii.dlss.render.RenderRuntime.setFgMultiplier] on a successful native
	 * record, immediately before it invalidates the surface configuration, so the swapchain the
	 * next frame creates is sized for the multiplier the plugin is now generating at.
	 */
	var numFramesToGenerate: Int = 1
		set(value) {
			field = maxOf(1, value)
		}

	/**
	 * The back-buffer count this multiplier needs, in both the recorded `DLSSGOptions` and the
	 * swapchain: [backBuffersFor] of [numFramesToGenerate].
	 */
	val requiredSwapchainImages: Int
		get() = backBuffersFor(numFramesToGenerate)

	/**
	 * Whether FG is effective right now: the user's mode AND a frame FG may compose on.
	 *
	 * Suspended frames read false here while [frameGenerationActive] keeps the user's mode,
	 * so a supported frame resumes exactly what the user's mode says - and a user-off policy
	 * reads false through the whole cycle.
	 */
	val effective: Boolean
		get() = frameGenerationActive && frameSupported

	/**
	 * The user's FG mode, regardless of whether this frame may compose on it.
	 *
	 * What the swapchain policy follows, and what a caller polling the plugin has to gate on:
	 * gating a poll on [effective] means a suspension stops the polling that would end it, so the
	 * suspension never lifts.
	 */
	val userEnabled: Boolean
		get() = frameGenerationActive

	/**
	 * Switches FG on or off and reports whether the mode changed.
	 *
	 * A real transition invalidates Minecraft's surface configuration exactly once, so the next
	 * frame recreates the swapchain under the new policy; a call that leaves the mode where it
	 * was is a no-op that invalidates nothing. A transition to off also runs [recordFrameGenerationOff],
	 * after the invalidation, so the options the plugin holds match the mode the swapchain was
	 * just recreated under.
	 */
	fun setFrameGenerationActive(active: Boolean): Boolean {
		if (active == frameGenerationActive) {
			return false
		}
		frameGenerationActive = active
		invalidateSurfaceConfiguration()
		if (!active) {
			recordFrameGenerationOff()
		}
		return true
	}

	/**
	 * Marks whether the current frame may compose FG, and reports whether the effective mode
	 * changed.
	 *
	 * A supported-to-unsupported flip suspends [effective] off while the user's mode stays
	 * untouched and runs [recordFrameGenerationOff] on exactly that transition; the frames in between
	 * change nothing, and a supported frame resumes without a record - the next FG frame's
	 * per-frame options record re-records eOn. The return answers true only when the user's mode
	 * is on - suspending an already-off policy changes nothing, and so does resuming a user-off
	 * one, which is the user-off precedence, and it is the same condition the record is under.
	 * Deliberately no surface invalidation: see the class comment, the swapchain policy follows
	 * the user's mode across a suspension.
	 */
	fun setCompositionSupported(supported: Boolean): Boolean {
		if (supported == frameSupported) {
			return false
		}
		frameSupported = supported
		if (!frameGenerationActive) {
			return false
		}
		if (!supported) {
			recordFrameGenerationOff()
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
		if (frameGenerationActive) maxOf(vanilla, requiredSwapchainImages) else vanilla

	companion object {
		/**
		 * Images beyond the ones one app frame presents: one being scanned out and one free to
		 * acquire while the pacer still holds the rest.
		 *
		 * Below this the presenter starves and DLSS-G cannot hold its generated frames back for
		 * equal spacing - the acquire blocks instead, and the interval the plugin divides is the
		 * blocked one. That is a mild wobble at 2x, where a single generated frame absorbs the
		 * whole error, and it compounds with the multiplier: at Nx the same irregular interval is
		 * cut N ways and every sub-interval carries all of it.
		 */
		const val PRESENT_HEADROOM = 2

		/**
		 * The back-buffer count a multiplier needs: one present per generated frame plus one for
		 * the rendered frame, plus [PRESENT_HEADROOM].
		 *
		 * A fixed 3 - which is what this policy declared while the multiplier was pinned at 2x -
		 * is already one short at 2x and two short at 3x, which is why 3x paces far worse than
		 * 2x rather than a little worse.
		 */
		fun backBuffersFor(numFramesToGenerate: Int): Int =
			numFramesToGenerate + 1 + PRESENT_HEADROOM

		/** [backBuffersFor] the default 2x multiplier. */
		const val DEFAULT_DECLARED_BACK_BUFFERS = 4
	}
}
