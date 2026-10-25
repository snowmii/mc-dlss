package me.snowmii.dlss.client

import me.snowmii.dlss.ReflexMode
import me.snowmii.dlss.RRModelPreset
import me.snowmii.dlss.SRMode
import me.snowmii.dlss.SRModelPreset
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

class DlssSettingsScreen(private val parent: Screen) : Screen(Component.translatable("mc-dlss.options.title")) {
	private lateinit var enabledButton: Button
	private lateinit var modeButton: Button
	private lateinit var presetButton: Button
	private lateinit var fgButton: Button
	private lateinit var multiplierButton: Button
	private lateinit var rrButton: Button
	private lateinit var rrPresetButton: Button
	private lateinit var reflexButton: Button

	override fun init() {
		val x = (width - BUTTON_WIDTH) / 2
		val y = height / 2 - 106
		val title = StringWidget(title, font)
		title.setPosition((width - title.width) / 2, y - 28)
		addRenderableWidget(title)

		enabledButton = optionButton(x, y, ::toggleEnabled)
		modeButton = optionButton(x, y + 24, ::cycleMode)
		presetButton = optionButton(x, y + 48, ::cyclePreset)
		fgButton = optionButton(x, y + 72, ::toggleFrameGeneration)
		multiplierButton = optionButton(x, y + 96) { controls()?.cycleFgMultiplier() }
		rrButton = optionButton(x, y + 120, ::toggleRayReconstruction)
		rrPresetButton = optionButton(x, y + 144, ::cycleRrPreset)
		reflexButton = optionButton(x, y + 168, ::cycleReflex)
		if (controls() == null) {
			val restart = StringWidget(Component.translatable("mc-dlss.options.restart_required"), font)
			restart.setPosition((width - restart.width) / 2, y + 188)
			addRenderableWidget(restart)
		}
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE) { onClose() }.bounds(x, y + 202, BUTTON_WIDTH, 20).build())
		refreshLabels()
	}

	override fun onClose() {
		minecraft.gui.setScreen(parent)
	}

	private fun optionButton(x: Int, y: Int, action: () -> Unit): Button =
		addRenderableWidget(Button.builder(Component.empty()) {
			action()
			refreshLabels()
		}.bounds(x, y, BUTTON_WIDTH, 20).build())

	private fun toggleEnabled() {
		val controls = controls()
		if (controls != null) controls.toggleEnabled() else user.enabled = !user.enabled
	}

	private fun cycleMode() {
		val controls = controls()
		if (controls != null) {
			controls.cycleQualityMode()
		} else {
			val modes = SRMode.entries
			user.qualityMode = modes[(modes.indexOf(user.qualityMode) + 1) % modes.size]
		}
	}

	private fun cyclePreset() {
		val controls = controls()
		if (controls != null) {
			controls.cyclePreset()
		} else {
			val presets = SRModelPreset.entries
			user.renderPreset = presets[(presets.indexOf(user.renderPreset) + 1) % presets.size]
		}
	}

	private fun toggleFrameGeneration() {
		val controls = controls()
		if (controls != null) controls.toggleFrameGeneration() else user.frameGeneration = !user.frameGeneration
	}

	private fun toggleRayReconstruction() {
		val controls = controls()
		if (controls != null) {
			controls.toggleRayReconstruction()
		} else {
			user.rayReconstruction = !user.rayReconstruction
		}
	}

	private fun cycleRrPreset() {
		val controls = controls()
		if (controls != null) {
			controls.cycleRrPreset()
		} else {
			val presets = RRModelPreset.entries
			user.rrModelPreset = presets[(presets.indexOf(user.rrModelPreset) + 1) % presets.size]
		}
	}

	private fun cycleReflex() {
		val controls = controls()
		if (controls != null) {
			controls.cycleReflexMode()
		} else {
			val modes = ReflexMode.entries
			user.reflexMode = modes[(modes.indexOf(user.reflexMode) + 1) % modes.size]
		}
	}

	private fun refreshLabels() {
		val controls = controls()
		enabledButton.message = label("mc-dlss.options.enabled", controls?.enabled ?: user.enabled)
		modeButton.message = valueLabel("mc-dlss.options.mode", (controls?.qualityMode ?: user.qualityMode).propertyValue)
		presetButton.message = valueLabel("mc-dlss.options.preset", (controls?.renderPreset ?: user.renderPreset).propertyValue)
		fgButton.message = label("mc-dlss.options.frame_generation", controls?.frameGenerationEnabled ?: user.frameGeneration)
		multiplierButton.message = valueLabel(
			"mc-dlss.options.fg_multiplier",
			controls?.let { "${it.frameGenerationMultiplier}x" } ?: Component.translatable("mc-dlss.options.in_world").string,
		)
		multiplierButton.active = controls != null
		rrButton.message = label(
			"mc-dlss.options.ray_reconstruction",
			controls?.rayReconstructionEnabled ?: user.rayReconstruction,
		)
		rrPresetButton.message = valueLabel(
			"mc-dlss.options.rr_preset",
			(controls?.rrModelPreset ?: user.rrModelPreset).propertyValue,
		)
		val reflexState = controls?.reflexState
		val reflexLabel = if (reflexState != null && !reflexState.lowLatencyAvailable()) {
			Component.translatable("mc-dlss.options.reflex").append(": ").append(Component.translatable("options.off")).append(" (N/A)")
		} else {
			valueLabel("mc-dlss.options.reflex", (controls?.reflexMode ?: user.reflexMode).propertyValue)
		}
		reflexButton.message = reflexLabel
	}

	private fun controls(): RuntimeControls? = ClientRuntime.active().activeControls()

	private fun label(key: String, value: Boolean): Component =
		Component.translatable(key).append(": ").append(Component.translatable(if (value) "options.on" else "options.off"))

	private fun valueLabel(key: String, value: String): Component = Component.translatable(key).append(": $value")

	private companion object {
		const val BUTTON_WIDTH = 220
		val user = ModConfig.user
	}
}
