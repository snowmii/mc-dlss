package me.snowmii.dlss.bridge

import me.snowmii.dlss.NativeBridge
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.vulkan.VK10
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.JAVA_FLOAT
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.nio.file.Files
import java.nio.file.Path

@NativeBridge
class ToneMapPresentationTest {
    @Test
    fun `direct presentation matches scratch copy exactly across reuse and resize`() {
        val library = compileProbe()
        Arena.ofConfined().use { arena ->
            val lookup = SymbolLookup.libraryLookup(library, arena)
            val linker = Linker.nativeLinker()
            fun bind(name: String, descriptor: FunctionDescriptor) =
                linker.downcallHandle(lookup.find(name).orElseThrow(), descriptor)
            val setup = bind("tone_map_setup", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT))
            val record = bind("tone_map_record", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_FLOAT))
            val copy = bind("tone_map_copy", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT))
            val close = bind("tone_map_close", FunctionDescriptor.ofVoid())

            HeadlessVulkanFixture(emptyList(), { _, _ -> emptyList() }, true).use { fixture ->
                assertTrue(fixture.validationEnabled(), "Khronos validation is required for this test")
                val sampled = VK10.VK_IMAGE_USAGE_SAMPLED_BIT or VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT
                val storage = VK10.VK_IMAGE_USAGE_STORAGE_BIT
                val colorAspect = VK10.VK_IMAGE_ASPECT_COLOR_BIT
                try {
                    for ((width, height) in listOf(17 to 11, 9 to 7)) {
                        val hdr = fixture.createEngineImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, sampled, colorAspect)
                        val reflection = fixture.createEngineImage(3, 2, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, sampled, colorAspect)
                        val scratch = fixture.createEngineImage(width, height, VK10.VK_FORMAT_R8G8B8A8_UNORM, storage, colorAspect)
                        val legacy = fixture.createEngineImage(width, height, VK10.VK_FORMAT_R8G8B8A8_UNORM, sampled, colorAspect)
                        val direct = fixture.createEngineImage(width, height, VK10.VK_FORMAT_R8G8B8A8_UNORM, sampled or storage, colorAspect)
                        setup.invokeWithArguments(fixture.deviceAddress(), hdr.view(), hdr.image(), width, height)
                        for (frame in 0..7) {
                            val command = fixture.allocateAndBeginCommandBuffer()
                            fixture.recordColorClear(command, hdr.image(), if (frame < 4) 0.002f else 2048f, 0.18f, 4f, 1f)
                            fixture.recordColorClear(command, reflection.image(), 0.25f, 2f, 0.5f, 1f)
                            fixture.recordColorClear(command, direct.image(), 1f, 0f, 1f, 1f)
                            fixture.recordGeneralLayoutBarrier(command, hdr)
                            fixture.recordGeneralLayoutBarrier(command, reflection)
                            val reflectionView = if (frame % 2 == 0) 0L else reflection.view()
                            val reflectionImage = if (frame % 2 == 0) 0L else reflection.image()
                            val exposure = listOf(0.25f, 1f, 4f, 0f)[frame % 4]
                            assertEquals(1, record.invokeWithArguments(command.address(), scratch.view(), scratch.image(), reflectionView, reflectionImage, exposure))
                            copy.invokeWithArguments(command.address(), scratch.image(), legacy.image(), width, height)
                            assertEquals(1, record.invokeWithArguments(command.address(), direct.view(), direct.image(), reflectionView, reflectionImage, exposure))
                            fixture.recordGeneralLayoutBarrier(command, direct)
                            fixture.endSubmitAndWait(command)
                            assertArrayEquals(fixture.readRgba8Image(legacy.image(), width, height),
                                fixture.readRgba8Image(direct.image(), width, height), 0f, "frame $frame at ${width}x$height")
                        }
                    }
                } finally {
                    close.invokeWithArguments()
                }
                assertTrue(fixture.validationErrors().isEmpty(), fixture.validationErrors().joinToString("\n"))
            }
        }
    }

    private fun compileProbe(): Path {
        val directory = Path.of("build", "tonemap-probe").toAbsolutePath()
        Files.createDirectories(directory)
        val library = directory.resolve("tonemap_probe.dll")
        val native = Path.of("native").toAbsolutePath()
        val objects = Path.of("build", "native", "obj").toAbsolutePath()
        val source = Path.of("src", "test", "native", "tonemap_probe.cpp").toAbsolutePath()
        val vulkan = Path.of(requireNotNull(System.getenv("VULKAN_SDK")))
        val sdkVersion = System.getProperty("mc.dlss.streamline-sdk-version") ?: "2.14.16"
        val sdk = System.getenv("STREAMLINE_SDK")?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".gradle", "caches", "streamline", sdkVersion)
        val vs = System.getenv("VSDEVCMD")?.let(Path::of) ?: Path.of(
            requireNotNull(System.getenv("ProgramFiles(x86)")), "Microsoft Visual Studio", "2022",
            "BuildTools", "Common7", "Tools", "VsDevCmd.bat",
        )
        val objectArguments = listOf("state.obj", "common.obj", "tonemap.obj")
            .joinToString(" ") { "\"${objects.resolve(it)}\"" }
        val command = "call \"$vs\" -arch=x64 -host_arch=x64 >nul && " +
            "cl.exe /nologo /std:c++17 /EHsc /LD /O2 /DNOMINMAX /I\"$native\" " +
            "/I\"${vulkan.resolve("Include")}\" /I\"${sdk.resolve("include")}\" " +
            "/I\"${sdk.resolve("external/ngx-sdk/include")}\" /Fe\"$library\" " +
            "\"$source\" $objectArguments /link \"${vulkan.resolve("Lib/vulkan-1.lib")}\""
        assertEquals(0, ProcessBuilder("cmd.exe", "/d", "/c", command)
            .directory(directory.toFile()).inheritIO().start().waitFor(), "production tone-map probe compilation")
        return library
    }
}
