package me.snowmii

import net.fabricmc.api.ModInitializer
import me.snowmii.dlss.config.ModConfig
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.streamline.ExtensionBootstrap
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

object McDlss : ModInitializer {
	const val MOD_ID: String = "mc-dlss"

	private val LOGGER = LoggerFactory.getLogger(MOD_ID)
	val startupConfig: DlssStartupConfig = ModConfig.from().startupConfig
	val session: DlssSession = DlssSession(startupConfig) { message -> LOGGER.warn(message) }

	override fun onInitialize() {
		// The SDK's native-library seam is injected here, once, from the mod's own config.
		// onInitialize runs strictly before any ExtensionBootstrap seam (the VulkanInstance
		// <init> mixins and ClientRuntime), so this read lands EARLIER than the lazy ModConfig
		// read it replaces at the first query seam — never later.
		ExtensionBootstrap.setNativeLibraryPath(startupConfig.nativeLibraryPath)
		startupConfig.warnings.forEach { warning -> LOGGER.warn("DLSS startup configuration: {}", warning) }
		LOGGER.info(
			"DLSS SR startup: enabled={} mode={} output={} native-library={}",
			startupConfig.enabled,
			startupConfig.qualityMode.propertyValue,
			startupConfig.outputDimensions,
			startupConfig.nativeLibraryPath ?: "external",
		)
		// LWJGL's Vulkan loading is intentionally not redirected to sl.interposer.dll. The mod uses
		// Streamline manual hooking: ExtensionBootstrap loads the runtime before Vulkan classes are
		// touched, then mc_dlss_activate_vulkan_proxies supplies the live instance, device, and
		// queue layout. StreamlineVulkanProvider remains an optional test-only interposer path,
		// enabled with -Pmc.dlss.vulkan-libname=<path>.
	}

	fun id(path: String): Identifier
		= Identifier.fromNamespaceAndPath(MOD_ID, path)
}
