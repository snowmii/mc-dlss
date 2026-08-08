package me.snowmii.dlss.sl

import java.nio.file.Files
import java.nio.file.Path
import me.snowmii.dlss.bridge.ExtensionBootstrap
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StreamlineBootstrapTest {
    @Test
    fun `Streamline owns pre-Vulkan requirements with stable manual-hook preferences`() {
        val source = Files.readString(Path.of("native", "mc_dlss_api.cpp"))
        assertTrue(source.contains("50f68c51-c7be-49bd-a875-f73045f88d27"))
        assertTrue(source.contains("sl::PreferenceFlags::eUseManualHooking"))
        assertTrue(source.contains("sl::RenderAPI::eVulkan"))
        assertTrue(source.contains("sl::kFeatureDLSS_G"))
        assertTrue(source.contains("sl::kFeatureReflex"))
        assertTrue(source.contains("slGetFeatureRequirements"))
        assertTrue(source.contains("if (g_state.streamlineInitialized) return collect_streamline_extensions"))

        // Production seam performs bootstrap before querying requirements. Empty extension sets
        // are valid when loaded plugins require only core Vulkan functionality.
        ExtensionBootstrap.queryInstanceExtensions()
    }
}
