package me.snowmii.dlss.ui

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.CommandEncoder
import com.mojang.blaze3d.systems.RenderSystem

/**
 * Scopes the in-world UI windows - the first-person hand and the GUI - in which the
 * transparent full-resolution [UiTarget] stands in for Minecraft's main target.
 *
 * The hand window opens at the head of `GameRenderer.renderItemInHand` and closes at its tail,
 * so `OutputTarget.MAIN_TARGET`'s draw-time resolution answers the UI target while hand and
 * item features draw, and the screen effects and 3D crosshair that run right after in
 * `GameRenderer.renderLevel` fall outside the window and keep the vanilla main target. The GUI
 * window opens at the head of `GuiRenderer.render`; its tail closes the window and runs the
 * frame's composite - one [UiComposite] that overlays the held UI target over the HUD-less
 * world still in the main target, handed in as both the source and the destination so the
 * composite skips its redundant base copy - so present, screenshots,
 * and every post-GUI consumer read the getter after the last window closes and keep the vanilla
 * main target with the frame's UI already baked in.
 *
 * Both windows share one held UI target, and the frame's transparent clear belongs to exactly
 * one of them: the hand window always clears - it is the frame's first UI window - and the GUI
 * window clears only when no hand window ran first, because the two windows never overlap in
 * the real frame. Hand drawing itself is gated inside vanilla's method on HUD visibility,
 * camera type, and game mode, so a hand window whose draw gate closed is just an empty clear.
 * The hand window closes through [end] without compositing: the world, the screen effects, and
 * the GUI still have to land in the main target after it.
 *
 * The getter seam resolves world before UI: an open world phase's scene target wins over both
 * windows, and outside all three the caller gets the vanilla main target. The windows never
 * overlap in the real frame - the world phase closes at the tail of `LevelRenderer.render`, the
 * hand window closes at the tail of `renderItemInHand`, both long before `GuiRenderer.render`
 * runs - so the ordering is defensive.
 *
 * Opening a window acquires the UI target at the main target's size and clears it to
 * transparent black and the reversed-Z far plane, so a frame nothing draws on composites the
 * world untouched. The encoder is injected so the whole window is verifiable off the render
 * thread; [forMinecraft] supplies the production pair.
 */
class UiPhase(
	private val target: UiTarget,
	/**
	 * Supplies the encoder the frame's transparent clear is recorded with. Injected so the
	 * window is drivable headless; production opens the render loop's device.
	 */
	private val encoder: () -> CommandEncoder = { RenderSystem.getDevice().createCommandEncoder() },
	/**
	 * Supplies the composite [endFrame] bakes the frame's UI with. Injected so the frame wiring
	 * is verifiable off the render thread; production resolves the render loop's sampler cache.
	 */
	private val composite: () -> UiComposite = { UiComposite() },
) : AutoCloseable {
	/** True between a window's [begin]/[beginHand] and [end]. */
	var isOpen: Boolean = false
		private set

	/**
	 * True once the frame's first UI window cleared the target, until the GUI window consumes
	 * the handoff: the clear-once-per-frame ownership. The hand window sets it, the GUI window
	 * reads it to decide whether its own clear is needed and consumes it, so the next frame's
	 * hand window clears again. The hand window ignores it and always clears, which also
	 * self-heals a handoff stranded by a frame that crashed between windows.
	 */
	private var clearedThisFrame = false

	/**
	 * The frame's real main target, stashed when a window opens and read back by [endFrame]
	 * after the window closes, when the getter no longer answers the UI target. Read at HEAD,
	 * while the redirect is still inactive, so it never sees the window's own override.
	 */
	private var frameMainTarget: RenderTarget? = null

	/**
	 * Target `GameRenderer.mainRenderTarget()` must answer with, or null when the caller gets
	 * the vanilla main target. Non-null only inside an open hand or GUI window.
	 */
	val uiTargetOverride: RenderTarget?
		get() = if (isOpen) target.current else null

	/**
	 * The held UI target, or null before the frame's first window allocated it.
	 *
	 * Read by the frame's DLSS-G composition at world-phase close, before the hand window
	 * runs: the target persists across frames once a window allocated it, so a steady-state
	 * frame's composition names the image the frame's own UI will be drawn into. A frame
	 * whose target does not exist yet - the first frame, or a resize frame whose held target
	 * is stale-sized - stays SR-only instead. Read-only and allocation-free, like every
	 * accessor here.
	 */
	val uiTarget: RenderTarget?
		get() = target.current

	/**
	 * Opens the hand window against the frame's main target: acquires the UI target at its size,
	 * always clears it for the frame, and makes the override visible.
	 *
	 * The hand window is the frame's first UI window - `renderItemInHand` runs inside
	 * `GameRenderer.renderLevel`, before `GuiRenderer.render` - so its clear is unconditional and
	 * marks the frame as cleared for the GUI window behind it. A main target with no measurable
	 * size never opens the window, so a degenerate frame keeps the vanilla target. An open
	 * window left behind by a failed frame - an exception between `renderItemInHand`'s head and
	 * tail skips the close, and a leaked window would answer the UI target for every later
	 * caller, present included - is dropped here rather than thrown on, exactly as
	 * [me.snowmii.dlss.render.WorldPhase] discards a stale world phase.
	 */
	fun beginHand(mainTarget: RenderTarget) {
		if (openWindow(mainTarget, clear = true)) {
			clearedThisFrame = true
		}
	}

	/**
	 * Opens the GUI window against the frame's main target: acquires the UI target at its size,
	 * clears it only when no hand window cleared it for this frame, and makes the override
	 * visible.
	 *
	 * The GUI window is the frame's last UI window, so it consumes the hand window's clear
	 * handoff: a frame whose hand drew starts the GUI with an already-cleared target, and a
	 * frame without a hand window - spectator, sleeping, hidden HUD, or any other frame where
	 * vanilla drew no hand - clears here. A main target with no measurable size never opens the
	 * window, so a degenerate frame keeps the vanilla target. An open window left behind by a
	 * failed frame is dropped here rather than thrown on, exactly as [beginHand] does.
	 */
	fun begin(mainTarget: RenderTarget) {
		openWindow(mainTarget, clear = !clearedThisFrame)
		clearedThisFrame = false
	}

	/**
	 * Opens one UI window: drops a window left open by a failed frame, acquires the UI target
	 * at the main target's size, clears it for the frame when this window owns the clear, and
	 * makes the override visible. Returns whether the window opened.
	 */
	private fun openWindow(mainTarget: RenderTarget, clear: Boolean): Boolean {
		isOpen = false
		if (mainTarget.width <= 0 || mainTarget.height <= 0) {
			return false
		}

		target.acquire(mainTarget.width, mainTarget.height)
		if (clear) {
			target.clear(encoder())
		}
		frameMainTarget = mainTarget
		isOpen = true
		return true
	}

	/** Closes the open window, restoring the vanilla main target. The held UI target survives. */
	fun end() {
		isOpen = false
	}

	/**
	 * Closes the GUI window and bakes the frame's UI over the HUD-less world, in that order: the
	 * window closes first, so the getter answers the vanilla main target again before the
	 * composite writes and no post-GUI consumer can read the UI target on a frame whose
	 * composite failed.
	 *
	 * The composite is handed the frame's main target - stashed when the window opened and
	 * still holding the HUD-less world, GUI blur included, that the GUI drew over - as both
	 * the HUD-less source and the destination. Aliasing the two makes the base copy
	 * redundant: a pass that samples the very target it writes into is invalid on every
	 * backend, and the destination already holds the world. The composite therefore overlays
	 * the held UI target over the world already in the main target, so the vanilla main
	 * target becomes the permanent presentation source before present, screenshots, and
	 * Tracy read the getter. The held UI target survives the bake.
	 *
	 * A frame whose window never opened - the menu, a null phase - or whose window failed to
	 * open has nothing to composite and leaves the main target untouched, as does a missing UI
	 * target or color view, which passes through the composite's own no-write guard.
	 */
	fun endFrame() {
		val windowWasOpen = isOpen
		val mainTarget = frameMainTarget
		isOpen = false
		frameMainTarget = null
		if (!windowWasOpen || mainTarget == null) {
			return
		}

		val ui = target.current ?: return
		composite().render(encoder(), ui, mainTarget, mainTarget)
	}

	override fun close() {
		// A close during an open window drops the window first, so no caller can keep answering
		// the UI target after its own target is gone.
		isOpen = false
		clearedThisFrame = false
		target.close()
	}

	companion object {
		/** Production wiring: the Minecraft-allocated UI target, cleared through the device encoder. */
		@JvmStatic
		fun forMinecraft(): UiPhase = UiPhase(
			target = UiTarget.forMinecraft(),
		)
	}
}
