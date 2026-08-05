package me.snowmii.dlss

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * M-6 rung: exercises production Java FFM and compiled NGX bridge on a real Vulkan device,
 * with every NGX-required Vulkan extension injected before instance and device creation.
 *
 * This is the pre-creation injection the contract v7 authorizes: query the exact extensions
 * from NVSDK_NGX_VULKAN_GetFeatureInstance/DeviceExtensionRequirements and enable them before
 * the Vulkan objects are created, exactly as the production bootstrap mixins do.
 *
 * Teardown order matters: native NGX shutdown must complete before Vulkan destruction, so the
 * native handle closes (inner) before the fixture closes (outer).
 */
class DlssRuntimeLoadTest {
	@Test
	fun `injected NGX extensions enable initialize support and dimension queries`(@TempDir dataPath: Path) {
		val repository = Path.of("").toAbsolutePath()
		val library = repository.resolve("build/native/mc_dlss.dll")
		val ngxRuntime = repository.resolve(
			"C:/Users/miuki/Development/NVIDIA/mc-dlss/dlss-sdk-v310.7.0/DLSS-310.7.0/lib/Windows_x86_64/rel",
		)
		assertEquals(true, Files.isRegularFile(library), "buildNativeDlss must produce mc_dlss.dll")
		assertEquals(true, Files.isDirectory(ngxRuntime), "Pinned NGX runtime directory must exist")

		// Instance extensions are queried before any Vulkan object exists; that handle can close
		// immediately because the query needed nothing but the loaded library.
		val instanceExtensions = DlssNative.open(library).use { it.queryInstanceExtensions() }
		assertTrue(instanceExtensions.isNotEmpty(), "NGX must require instance extensions")

		HeadlessVulkanFixture(instanceExtensions) { vkInstance, vkPhysicalDevice ->
			// Device extensions are queried against the live instance + physical device, before
			// the device itself is created, mirroring the production bootstrap mixin order.
			DlssNative.open(library).use { it.queryDeviceExtensions(vkInstance, vkPhysicalDevice) }
		}.use { vulkan ->
			DlssNative.open(library).use { native ->
				val config = DlssStartupConfig(
					enabled = true,
					qualityMode = DlssQualityMode.QUALITY,
					outputDimensions = DlssDimensions(2560, 1440),
					sdkPath = ngxRuntime,
					nativeLibraryPath = library,
					dataPath = dataPath,
					warnings = emptyList(),
				)
				val session = DlssSession(config)
				val dimensions = DlssLifecycleAdapter(session, native).initialize(
					vulkan.instanceAddress(),
					vulkan.physicalDeviceAddress(),
					vulkan.deviceAddress(),
					ngxRuntime,
					dataPath,
				)
				assertNotNull(dimensions, session.failure?.diagnostic())
				assertEquals(DlssSessionState.READY, session.state)
				assertTrue(dimensions!!.width in 1..2560)
				assertTrue(dimensions.height in 1..1440)
			}
		}
	}
}
