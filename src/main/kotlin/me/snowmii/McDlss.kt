package me.snowmii

import net.fabricmc.api.ModInitializer
import me.snowmii.dlss.bridge.StreamlineVulkanProvider
import me.snowmii.dlss.config.ModConfig
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

object McDlss : ModInitializer {
	const val MOD_ID: String = "mc-dlss"

	private val LOGGER = LoggerFactory.getLogger(MOD_ID)
	val startupConfig: DlssStartupConfig = ModConfig.from().startupConfig
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
		// Redirect LWJGL's Vulkan loading to the staged sl.interposer.dll before any Vulkan
		// class is touched (the first touch is VulkanInstance.<init>, well after this). The
		// redirect is unconditional, mirroring the unconditional slInit at the instance seam;
		// a missing staged runtime degrades to a warning here rather than failing the loader.
		try {
			val interposer = StreamlineVulkanProvider.redirectToInterposer()
			LOGGER.info("Streamline Vulkan interposer staged at {}", interposer)
		} catch (error: Throwable) {
			LOGGER.warn("Streamline Vulkan interposer redirect failed; SL Vulkan proxies inactive", error)
		}
	}

	fun id(path: String): Identifier
		= Identifier.fromNamespaceAndPath(MOD_ID, path)
}
