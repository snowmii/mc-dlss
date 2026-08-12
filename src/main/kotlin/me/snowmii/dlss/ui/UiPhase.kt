package me.snowmii.dlss.ui

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.CommandEncoder
import com.mojang.blaze3d.systems.RenderSystem

/**
 * Scopes one in-world GUI window, the only window in which the transparent full-resolution
 * [UiTarget] stands in for Minecraft's main target.
 *
 * The window opens at the head of `GuiRenderer.render` and closes at its tail, mirroring how
 * [me.snowmii.dlss.render.WorldPhase] brackets `LevelRenderer.render`. While it is open
 * `GameRenderer.mainRenderTarget()` answers the UI target, so `GuiRenderer.draw`'s draw ranges -
 * the only getter readers inside the window - land in the transparent full-resolution target
 * instead of the world target. The GUI blur reads GameRenderer's private field rather than the
 * getter, and the hand and item draw earlier in `GameRenderer.renderLevel`, so both keep
 * sampling the vanilla main target; present, screenshots, and every post-GUI consumer read the
 * getter after the window closes and keep the vanilla target too.
 *
 * The getter seam resolves world before UI: an open world phase's scene target wins over the
 * GUI window, and outside both windows the caller gets the vanilla main target. The two windows
 * never overlap in the real frame - the world phase closes at the tail of `LevelRenderer.render`,
 * long before `GuiRenderer.render` runs - so the ordering is defensive.
 *
 * Opening the window acquires the UI target at the main target's size and clears it to
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
) : AutoCloseable {
	/** True between [begin] and [end]. */
	var isOpen: Boolean = false
		private set

	/**
	 * Target `GameRenderer.mainRenderTarget()` must answer with, or null when the caller gets
	 * the vanilla main target. Non-null only inside an open GUI window.
	 */
	val uiTargetOverride: RenderTarget?
		get() = if (isOpen) target.current else null

	/**
	 * Opens the GUI window against the frame's main target: acquires the UI target at its size,
	 * clears it for the frame, and makes the override visible.
	 *
	 * A main target with no measurable size never opens the window, so a degenerate frame keeps
	 * the vanilla target. An open window left behind by a failed frame - an exception between
	 * `GuiRenderer.render`'s head and tail skips the close, and a leaked window would answer the
	 * UI target for every later caller, present included - is dropped here rather than thrown on,
	 * exactly as [me.snowmii.dlss.render.WorldPhase] discards a stale world phase.
	 */
	fun begin(mainTarget: RenderTarget) {
		isOpen = false
		if (mainTarget.width <= 0 || mainTarget.height <= 0) {
			return
		}

		target.acquire(mainTarget.width, mainTarget.height)
		target.clear(encoder())
		isOpen = true
	}

	/** Closes the GUI window, restoring the vanilla main target. The held UI target survives. */
	fun end() {
		isOpen = false
	}

	override fun close() {
		// A close during an open window drops the window first, so no caller can keep answering
		// the UI target after its own target is gone.
		isOpen = false
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
