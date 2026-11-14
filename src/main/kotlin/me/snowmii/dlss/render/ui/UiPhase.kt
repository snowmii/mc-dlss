package me.snowmii.dlss.render.ui

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.renderpearl.api.commands.CommandEncoder
import com.mojang.blaze3d.systems.RenderSystem

class UiPhase(
	private val target: UiTarget,
	private val encoder: () -> CommandEncoder = { RenderSystem.getDevice().createCommandEncoder() },
	private val composite: () -> UiComposite = { UiComposite() },
) : AutoCloseable {
	var isOpen: Boolean = false
		private set

	private var clearedThisFrame = false
	private var seeThroughClearedThisFrame = false

	private var frameMainTarget: RenderTarget? = null

	val uiTargetOverride: RenderTarget?
		get() = if (isOpen) target.currentUiTarget else null

	val uiTarget: RenderTarget?
		get() = target.currentUiTarget

	fun beginSeeThrough(mainTarget: RenderTarget): RenderTarget? {
		if (openWindow(mainTarget, clear = true)) {
			clearedThisFrame = true
			seeThroughClearedThisFrame = true
			return target.currentUiTarget
		}
		return null
	}

	fun endSeeThrough() {
		isOpen = false
	}

	fun beginHand(mainTarget: RenderTarget) {
		val clear = !seeThroughClearedThisFrame
		seeThroughClearedThisFrame = false
		if (openWindow(mainTarget, clear = clear)) {
			clearedThisFrame = true
		}
	}

	fun renderHand(mainTarget: RenderTarget, render: Runnable) {
		beginHand(mainTarget)
		try {
			render.run()
		} finally {
			end()
		}
	}

	fun begin(mainTarget: RenderTarget) {
		val clear = !clearedThisFrame
		clearedThisFrame = false
		seeThroughClearedThisFrame = false
		openWindow(mainTarget, clear = clear)
	}

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

	fun end() {
		isOpen = false
	}

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
		isOpen = false
		clearedThisFrame = false
		seeThroughClearedThisFrame = false
		frameMainTarget = null
		target.close()
	}

	companion object {
		@JvmStatic
		fun forMinecraft(): UiPhase = UiPhase(
			target = UiTarget.forMinecraft(),
		)
	}
}
