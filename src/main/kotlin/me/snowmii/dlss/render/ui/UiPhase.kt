package me.snowmii.dlss.render.ui

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.CommandEncoder
import com.mojang.blaze3d.systems.RenderSystem

/**
 * First-person hand and GUI windows where the transparent full-resolution [UiTarget] stands
 * in for Minecraft's main target.
 *
 * Hand: `GameRenderer.renderItemInHand` HEAD→TAIL. Screen effects and the 3D crosshair run
 * after in `renderLevel` and stay on vanilla main. GUI: `GuiRenderer.render` HEAD; TAIL
 * closes and composites UI over the HUD-less world already in the main target (same target
 * as source and dest, so the base copy is skipped).
 *
 * One held UI target. Hand always clears (first UI window); GUI clears only if hand did not.
 * Hand [end] does not composite — world, effects, and GUI still write the main target after.
 * Getter: open world phase wins; else UI window; else vanilla. Real frames never overlap
 * those windows; world-before-UI is defensive.
 *
 * Acquire at main-target size; clear to transparent black + reversed-Z far. Encoder injected
 * for headless; [forMinecraft] is production.
 */
class UiPhase(
	private val target: UiTarget,
	/** Injected so the window is drivable headless; production uses the render-loop device. */
	private val encoder: () -> CommandEncoder = { RenderSystem.getDevice().createCommandEncoder() },
	/** Injected so composite wiring is testable off the render thread. */
	private val composite: () -> UiComposite = { UiComposite() },
) : AutoCloseable {
	var isOpen: Boolean = false
		private set

	/**
	 * Clear-once-per-frame: hand sets it; GUI reads then clears it. Hand always clears, which
	 * also heals a handoff stranded by a crash between windows.
	 */
	private var clearedThisFrame = false

	/**
	 * Real main target, stashed at window open. Read at HEAD while the redirect is still
	 * inactive, so it never sees the window's own override. [endFrame] reads it after close.
	 */
	private var frameMainTarget: RenderTarget? = null

	/**
	 * Target `GameRenderer.mainRenderTarget()` must answer with, or null when the caller gets
	 * the vanilla main target. Non-null only inside an open hand or GUI window.
	 */
	val uiTargetOverride: RenderTarget?
		get() = if (isOpen) target.currentUiTarget else null

	/**
	 * Held UI target, or null before the first window allocated it. Read at world-phase close
	 * (before the hand window): the target persists across frames, so composition names the
	 * image this frame's UI will draw into. Missing or stale-sized: SR-only.
	 */
	val uiTarget: RenderTarget?
		get() = target.currentUiTarget

	/**
	 * First UI window: always clears. Drops a window left open by a failed frame (exception
	 * between renderItemInHand HEAD/TAIL) rather than leaking the override to present.
	 * Zero-size main target never opens.
	 */
	fun beginHand(mainTarget: RenderTarget) {
		if (openWindow(mainTarget, clear = true)) {
			clearedThisFrame = true
		}
	}

	/**
	 * Last UI window: clears only if hand did not. Consumes the handoff so the next frame's
	 * hand clears again. Drops a stale open window like [beginHand].
	 */
	fun begin(mainTarget: RenderTarget) {
		openWindow(mainTarget, clear = !clearedThisFrame)
		clearedThisFrame = false
	}

	/**
	 * Drops a stale open window, acquires at main-target size, clears when this window owns
	 * the clear. Zero-size never opens.
	 */
	private fun openWindow(mainTarget: RenderTarget, clear: Boolean): Boolean {
		isOpen = false
		if (mainTarget.width <= 0 || mainTarget.height <= 0) {
			return false
		}

		target.acquireUiTarget(mainTarget.width, mainTarget.height)
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
	 * Close the window first, then bake UI over HUD-less world. Close-first so a failed
	 * composite cannot leave post-GUI consumers reading the UI target.
	 *
	 * Main target (stashed at open) is both HUD-less source and dest: destination already
	 * holds the world, and sampling the target a pass writes is invalid. Overlay only.
	 * Held UI target survives. No-op if the window never opened.
	 */
	fun endFrame() {
		val windowWasOpen = isOpen
		val mainTarget = frameMainTarget
		isOpen = false
		frameMainTarget = null
		if (!windowWasOpen || mainTarget == null) {
			return
		}

		val ui = target.currentUiTarget ?: return
		composite().render(encoder(), ui, mainTarget, mainTarget)
	}

	override fun close() {
		// Drop the window before releasing the target so no caller keeps answering the UI target.
		isOpen = false
		clearedThisFrame = false
		target.close()
	}

	companion object {
		@JvmStatic
		fun forMinecraft(): UiPhase = UiPhase(
			target = UiTarget.forMinecraft(),
		)
	}
}
