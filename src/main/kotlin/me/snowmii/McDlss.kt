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
		// LWJGL's Vulkan loading is deliberately NOT redirected to sl.interposer.dll.
		//
		// Pointing org.lwjgl.vulkan.libname at the interposer is the automatic-hooking style of
		// integration, and it contradicts the eUseManualHooking flag this mod initializes SL
		// with. Measured on the live FG rung: with the redirect, DLSS-G's
		// slHookVkCreateSwapchainKHR never fires and slSetVulkanInfo fails outright; without
		// it, the hook fires and presentCommon reaches "interpolation enabled". The redirect is
		// also the one process-level difference between the passing rung and the in-game
		// session that reported presented=0 status=0 fence=0, where sl.log carried
		// "Streamline presentCommon() was not observed".
		//
		// SL still reaches Vulkan without the redirect: ExtensionBootstrap loads sl.common.dll
		// and sl.interposer.dll into the process before any Vulkan class is touched, and
		// mc_dlss_activate_vulkan_proxies hands SL the live instance/device/queue layout
		// through slSetVulkanInfo.
		//
		// Toggle it back with -Pmc.dlss.vulkan-libname=<path> on the test task if this needs
		// re-measuring; StreamlineVulkanProvider is retained for that experiment.
	}

	fun id(path: String): Identifier
		= Identifier.fromNamespaceAndPath(MOD_ID, path)
}
