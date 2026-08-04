package me.snowmii

import net.fabricmc.api.ModInitializer
import me.snowmii.dlss.DlssSession
import me.snowmii.dlss.DlssStartupConfig
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

object McDlss : ModInitializer {
	const val MOD_ID: String = "mc-dlss"

	private val LOGGER = LoggerFactory.getLogger(MOD_ID)
	val startupConfig: DlssStartupConfig = DlssStartupConfig.from()
	val session: DlssSession = DlssSession(startupConfig) { message -> LOGGER.warn(message) }

	override fun onInitialize() {
		startupConfig.warnings.forEach { warning -> LOGGER.warn("DLSS startup configuration: {}", warning) }
		LOGGER.info(
			"DLSS SR startup: enabled={} mode={} output={} native-library={}",
			startupConfig.enabled,
			startupConfig.qualityMode.propertyValue,
			startupConfig.outputDimensions,
			startupConfig.nativeLibraryPath ?: "external",
		)
	}

	fun id(path: String): Identifier
		= Identifier.fromNamespaceAndPath(MOD_ID, path)
}
