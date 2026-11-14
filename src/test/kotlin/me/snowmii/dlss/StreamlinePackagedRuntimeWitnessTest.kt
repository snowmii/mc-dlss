package me.snowmii.dlss

import java.net.JarURLConnection
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StreamlinePackagedRuntimeWitnessTest {

	@Test
	fun `jar-only loader extracts the whole colocated flat runtime into one temp dir`() {
		val sdkEntry = StreamlinePackagedRuntimeWitnessTest::class.java.classLoader
			.getResource(SDK_CLASS_RESOURCE)
			?: error("streamline SDK jar missing from the test classpath")
		assertEquals("jar", sdkEntry.protocol, "SDK must resolve from its packaged jar")
		val sdkJar = (sdkEntry.openConnection() as JarURLConnection).jarFileURL
		assertTrue(Regex(".*streamline-.*\\.jar").matches(sdkJar.file), "SDK jar shape: ${sdkJar.file}")

		val loader = URLClassLoader(arrayOf(sdkJar), ClassLoader.getPlatformClassLoader())
		loader.use { loader ->
			val bootstrap = loader.loadClass(SDK_CLASS_NAME)
			assertSame(loader, bootstrap.classLoader, "platform parent cannot define the SDK class")
			val setNativeLibraryPath = bootstrap.getMethod("setNativeLibraryPath", Path::class.java)
			val nativeLibrary = bootstrap.getMethod("nativeLibrary")
			val streamlinedRuntimeDirectory = bootstrap.getMethod("streamlineRuntimeDirectory")

			setNativeLibraryPath.invoke(null, null as Path?)
			val library = nativeLibrary.invoke(null) as Path
			val runtime = streamlinedRuntimeDirectory.invoke(null) as Path

			assertEquals(runtime, library.parent, "one shared extracted temp directory")
			assertEquals(runtime.resolve("mc_dlss.dll"), library, "bridge extracted beside the runtime")
			val tmpdir = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()
			assertTrue(runtime.startsWith(tmpdir), "resolved by extraction under java.io.tmpdir: $runtime")

			val flatRuntime = listOf(
				"mc_dlss.dll",
				"sl.interposer.dll", "sl.common.dll", "sl.dlss.dll", "sl.dlss_d.dll", "sl.dlss_g.dll",
				"sl.reflex.dll", "sl.pcl.dll",
				"nvngx_dlss.dll", "nvngx_dlssd.dll", "nvngx_dlssg.dll", "NvLowLatencyVk.dll"
			)
			Files.list(runtime).use { entries ->
				assertEquals(flatRuntime.size.toLong(), entries.count(), "no stray entries in $runtime")
			}
			for (name in flatRuntime) {
				assertTrue(Files.isRegularFile(runtime.resolve(name)), "extracted dir missing $name")
			}
		}
	}

	private companion object {
		private const val SDK_CLASS_NAME = "me.snowmii.streamline.ExtensionBootstrap"
		private const val SDK_CLASS_RESOURCE = "me/snowmii/streamline/ExtensionBootstrap.class"
	}
}
