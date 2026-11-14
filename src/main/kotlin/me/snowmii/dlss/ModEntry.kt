package me.snowmii.dlss

import me.snowmii.dlss.client.ModConfig
import me.snowmii.dlss.mixin.DebugScreenEntriesInvoker
import me.snowmii.dlss.readout.DlssDebugEntry
import me.snowmii.dlss.readout.DlssPacingEntry
import me.snowmii.streamline.Streamline
import net.fabricmc.api.ClientModInitializer
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

object ModEntry : ClientModInitializer {
	const val MOD_ID: String = "mc-dlss"

	private val LOGGER = LoggerFactory.getLogger(MOD_ID)
	val startupConfig: DlssStartupConfig = ModConfig.fromSystemProperties().startupConfig
	val session: DlssSession = DlssSession(startupConfig) { message -> LOGGER.warn(message) }

	override fun onInitializeClient() {
		Streamline.configure(startupConfig.nativeLibraryPath)
		me.snowmii.dlss.streamline.VulkanBootstrap.install()
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
	}

	fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(MOD_ID, path)
}
