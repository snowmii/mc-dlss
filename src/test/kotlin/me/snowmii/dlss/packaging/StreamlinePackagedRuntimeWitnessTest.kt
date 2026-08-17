package me.snowmii.dlss.packaging

import java.net.JarURLConnection
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Witnesses the packaged (nested-jar) native-runtime seam in the Fabric nested-jar shape: a
 * restricted classloader whose universe is exactly the SDK jar, parented at the platform
 * classloader so nothing in this test's own classpath can serve the class, defines the SDK's
 * bootstrap class from the jar and must resolve the native-library and Streamline-runtime
 * seams by extracting the entire colocated flat runtime once into a single temp directory.
 *
 * Reflect-only by design: the SDK package is never imported, so the emulated class is the one
 * the restricted loader defines, not the copy this test's classpath also carries. Only the
 * resolution seams are reached — resolution extracts, it never loads — so no native code runs
 * here and the shared `test` worker is the right home, without `--enable-native-access`.
 */
class StreamlinePackagedRuntimeWitnessTest {

	@Test
	fun `jar-only loader extracts the whole colocated flat runtime into one temp dir`() {
		// The SDK rides the test classpath as its packaged jar (a project dependency resolves
		// to the runtimeElements jar). Derive the jar URL from a resource declaration inside
		// it: the same shape a nested META-INF/jars/streamline-1.0.0.jar entry resolves under.
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

			// A fresh class has no injected path; reset is belt-and-braces all the same.
			setNativeLibraryPath.invoke(null, null as Path?)
			val library = nativeLibrary.invoke(null) as Path
			val runtime = streamlinedRuntimeDirectory.invoke(null) as Path

			// Both seams resolve through the one extraction, not an in-place loose file.
			assertEquals(runtime, library.parent, "one shared extracted temp directory")
			assertEquals(runtime.resolve("mc_dlss.dll"), library, "bridge extracted beside the runtime")
			val tmpdir = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()
			assertTrue(runtime.startsWith(tmpdir), "resolved by extraction under java.io.tmpdir: $runtime")

			// Every entry of the colocated flat runtime must land beside the bridge.
			val flatRuntime = listOf(
				"mc_dlss.dll",
				"sl.interposer.dll", "sl.common.dll", "sl.dlss.dll", "sl.dlss_g.dll", "sl.reflex.dll",
				"sl.pcl.dll",
				"nvngx_dlss.dll", "nvngx_dlssg.dll", "NvLowLatencyVk.dll"
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
		// This witness reaches only resolution seams that extract without loading, so it needs
		// no bridge marker and no fork of its own.
		private const val SDK_CLASS_NAME = "me.snowmii.streamline.ExtensionBootstrap"
		private const val SDK_CLASS_RESOURCE = "me/snowmii/streamline/ExtensionBootstrap.class"
	}
}
