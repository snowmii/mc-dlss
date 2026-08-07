package me.snowmii.dlss.sl

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import me.snowmii.dlss.bridge.Native

class StreamlineRuntimeLoadTest {
    @Test
    fun `build links Streamline and stages pinned runtime`() {
        val buildScript = Files.readString(Path.of("build.gradle.kts"))
        assertTrue(buildScript.contains("lib/x64/sl.interposer.lib"))
        assertTrue(buildScript.contains("streamline-sdk-v2.12.0"))

        val resources = Path.of("build", "resources", "main", "assets", "mc-dlss", "native")
        val runtime = resources.resolve("streamline")
        listOf(
            "sl.interposer.dll",
            "sl.common.dll",
            "sl.dlss.dll",
            "sl.dlss_g.dll",
            "sl.reflex.dll",
            "nvngx_dlss.dll",
            "nvngx_dlssg.dll",
            "NvLowLatencyVk.dll",
        ).forEach { name ->
            val binary = runtime.resolve(name)
            assertTrue(Files.isRegularFile(binary), "Streamline runtime binary not staged: $name")
            Files.newInputStream(binary).use { input ->
                assertArrayEquals(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte()), input.readNBytes(2), "$name is not a PE binary")
            }
        }

        Native.open(resources.resolve("mc_dlss.dll")).close()
    }
}
