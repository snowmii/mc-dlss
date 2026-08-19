package me.snowmii.dlss

import me.snowmii.dlss.client.ClientRuntime
import me.snowmii.dlss.client.ModConfig
import me.snowmii.dlss.mixin.DebugScreenEntriesInvoker
import me.snowmii.dlss.readout.DlssDebugEntry
import me.snowmii.dlss.readout.DlssPacingEntry
import me.snowmii.dlss.readout.DlssDebugSnapshot
import me.snowmii.dlss.stress.StressRuntime
import me.snowmii.streamline.Streamline
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory

object ModEntry : ClientModInitializer {
	const val MOD_ID: String = "mc-dlss"

	private val LOGGER = LoggerFactory.getLogger(MOD_ID)
	val startupConfig: DlssStartupConfig = ModConfig.fromSystemProperties().startupConfig
	val session: DlssSession = DlssSession(startupConfig) { message -> LOGGER.warn(message) }

	override fun onInitializeClient() {
		// This runs before Vulkan construction mixins, so Streamline is configured before any
		// native graphics seam can ask for it.
		Streamline.configure(startupConfig.nativeLibraryPath)
		startupConfig.warnings.forEach { warning -> LOGGER.warn("DLSS startup configuration: {}", warning) }
		LOGGER.info(
			"DLSS SR startup: enabled={} mode={} output={} native-library={}",
			startupConfig.enabled,
			startupConfig.qualityMode.propertyValue,
			startupConfig.outputDimensions,
			startupConfig.nativeLibraryPath ?: "external",
		)

		DebugScreenEntriesInvoker.mcDlssRegister(id("dlss"), DlssDebugEntry)
		DebugScreenEntriesInvoker.mcDlssRegister(id("dlss_pacing"), DlssPacingEntry)
		registerControls()
	}

	private fun registerControls() {
		val category = KeyMapping.Category.register(id("controls"))
		val bindings = listOf(
			binding(category, "toggle", GLFW.GLFW_KEY_F6) { ClientRuntime.active().activeControls()?.toggleEnabled() },
			binding(category, "mode", GLFW.GLFW_KEY_F7) { ClientRuntime.active().activeControls()?.cycleQualityMode() },
			binding(category, "preset", GLFW.GLFW_KEY_F8) { ClientRuntime.active().activeControls()?.cyclePreset() },
			binding(category, "stress", GLFW.GLFW_KEY_F9) {
				StressRuntime.togglePasses()?.let { message ->
					LOGGER.info(message)
					DlssDebugSnapshot.record(message)
				}
			},
			binding(category, "frame_generation", GLFW.GLFW_KEY_F10) {
				ClientRuntime.active().activeControls()?.toggleFrameGeneration()
			},
			binding(category, "fg_multiplier", GLFW.GLFW_KEY_F12) {
				ClientRuntime.active().activeControls()?.cycleFgMultiplier()
			},
		)
		ClientTickEvents.END_CLIENT_TICK.register { client ->
			val debugModifierDown = client.options.keyDebugModifier.isDown
			bindings.forEach { (key, action) ->
				while (key.consumeClick()) {
					if (!debugModifierDown) action()
				}
			}
		}
	}

	private fun binding(
		category: KeyMapping.Category,
		name: String,
		defaultKey: Int,
		action: () -> Unit,
	): Pair<KeyMapping, () -> Unit> =
		KeyMappingHelper.registerKeyMapping(KeyMapping("key.mc-dlss.$name", defaultKey, category)) to action

	fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(MOD_ID, path)
}
