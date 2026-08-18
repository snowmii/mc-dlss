package me.snowmii.dlss.client

import me.snowmii.dlss.config.ClientConfig
import me.snowmii.dlss.session.SRMode
import me.snowmii.dlss.session.SRModelPreset
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

	override fun init() {
		val x = (width - BUTTON_WIDTH) / 2
		val y = height / 2 - 70
		val title = StringWidget(title, font)
		title.setPosition((width - title.width) / 2, y - 28)
		addRenderableWidget(title)

		enabledButton = optionButton(x, y, ::toggleEnabled)
		modeButton = optionButton(x, y + 24, ::cycleMode)
		presetButton = optionButton(x, y + 48, ::cyclePreset)
		fgButton = optionButton(x, y + 72, ::toggleFrameGeneration)
		multiplierButton = optionButton(x, y + 96) { controls()?.cycleFgMultiplier() }
		if (controls() == null) {
			val restart = StringWidget(Component.translatable("mc-dlss.options.restart_required"), font)
			restart.setPosition((width - restart.width) / 2, y + 116)
			addRenderableWidget(restart)
		}
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE) { onClose() }.bounds(x, y + 130, BUTTON_WIDTH, 20).build())
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
		if (controls != null) controls.toggleEnabled() else config.setEnabled(!config.enabled())
	}

	private fun cycleMode() {
		val controls = controls()
		if (controls != null) {
			controls.cycleQualityMode()
		} else {
			val current = SRMode.entries.firstOrNull { it.propertyValue == config.qualityMode() } ?: SRMode.QUALITY
			config.setQualityMode(SRMode.entries[(SRMode.entries.indexOf(current) + 1) % SRMode.entries.size].propertyValue)
		}
	}

	private fun cyclePreset() {
		val controls = controls()
		if (controls != null) {
			controls.cyclePreset()
		} else {
			val current = SRModelPreset.fromPropertyValue(config.renderPreset()) ?: SRModelPreset.M
			config.setRenderPreset(
				SRModelPreset.entries[(SRModelPreset.entries.indexOf(current) + 1) % SRModelPreset.entries.size].propertyValue,
			)
		}
	}

	private fun toggleFrameGeneration() {
		val controls = controls()
		if (controls != null) controls.toggleFrameGeneration() else config.setFrameGeneration(!config.frameGeneration())
	}

	private fun refreshLabels() {
		val controls = controls()
		enabledButton.message = label("mc-dlss.options.enabled", controls?.enabled ?: config.enabled())
		modeButton.message = valueLabel("mc-dlss.options.mode", controls?.qualityMode?.propertyValue ?: config.qualityMode())
		presetButton.message = valueLabel("mc-dlss.options.preset", controls?.renderPreset?.propertyValue ?: config.renderPreset())
		fgButton.message = label("mc-dlss.options.frame_generation", controls?.frameGenerationEnabled ?: config.frameGeneration())
		multiplierButton.message = valueLabel(
			"mc-dlss.options.fg_multiplier",
			controls?.let { "${it.frameGenerationMultiplier}x" } ?: Component.translatable("mc-dlss.options.in_world").string,
		)
		multiplierButton.active = controls != null
	}

	private fun controls(): RuntimeControls? = ClientRuntime.active().activeControls()

	private fun label(key: String, value: Boolean): Component =
		Component.translatable(key).append(": ").append(Component.translatable(if (value) "options.on" else "options.off"))

	private fun valueLabel(key: String, value: String): Component = Component.translatable(key).append(": $value")

	private companion object {
		const val BUTTON_WIDTH = 220
		val config: ClientConfig = ClientConfig.INSTANCE
	}
}
