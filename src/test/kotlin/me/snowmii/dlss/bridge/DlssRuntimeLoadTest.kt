package me.snowmii.dlss.bridge
import me.snowmii.streamline.Dimensions;
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.SRMode
import me.snowmii.dlss.session.DlssSessionState
import me.snowmii.dlss.session.LifecycleAdapter

import java.nio.file.Files
import java.nio.file.Path
import me.snowmii.dlss.NativeBridge
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Live ABI rung: production Java FFM and the compiled bridge on a real Vulkan device, with the
 * Streamline-required extensions injected before instance and device creation.
 *
 * This is the pre-creation injection the contract authorizes: query the exact extensions from
 * slGetFeatureRequirements (through the production bootstrap seams) and enable them before the
 * Vulkan objects are created, exactly as the production bootstrap mixins do. The device holds
 * the merged queue layout, proxy activation records it with slSetVulkanInfo, and the initialize
 * surface validates and records the tuple before the optimal-dimension query answers.
 *
 * Teardown order matters: native teardown must complete before Vulkan destruction, so the
 * native handle closes (inner) before the fixture closes (outer).
 */
@NativeBridge
class DlssRuntimeLoadTest {
	@Test
	fun `injected Streamline extensions enable initialize support and dimension queries`(@TempDir dataPath: Path) {
		val repository = Path.of("").toAbsolutePath()
		val library = repository.resolve("build/native/mc_dlss.dll")
		assertEquals(true, Files.isRegularFile(library), "buildNativeDlss must produce mc_dlss.dll")

		// Instance extensions are queried before any Vulkan object exists; the production seam
		// bootstraps Streamline first, which is what the query now requires.
		val instanceExtensions = ExtensionBootstrap.queryInstanceExtensions()

		// The queue requirements come from a throwaway bridge closed before the device exists;
		// the fixture then OUTLIVES the bridge so Native.close's mc_dlss_close runs while the
		// Vulkan device is still alive.
		val requirements = Native.open(library).use { bridge ->
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				bridge.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
			)
			bridge.queryQueueRequirements()
		}
		val graphicsFamily = probeGraphicsQueueFamily()
		val extras = requirements.graphicsQueues + requirements.computeQueues

		HeadlessVulkanFixture(
			instanceExtensions,
			{ vkInstance, vkPhysicalDevice ->
				// Device extensions are queried against the live instance + physical device,
				// before the device itself is created, mirroring the production mixin order.
				val extensions = mutableListOf<String>()
				ExtensionBootstrap.addDeviceExtensions(extensions, vkInstance, vkPhysicalDevice)
				extensions
			},
			false,
			mapOf(graphicsFamily to extras),
		).use { vulkan ->
			Native.open(library).use { native ->
				// Bootstrap is idempotent across bridge instances: the runtime is already up.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					native.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
				)
				// The fixture creates one host queue in the family, so Streamline's own queues
				// start at index 1 - right after the host's, as slSetVulkanInfo records them.
				val hostQueueCount = 1
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					native.activateVulkanProxies(
						vulkan.instanceAddress(),
						vulkan.physicalDeviceAddress(),
						vulkan.deviceAddress(),
						graphicsFamily,
						hostQueueCount,
						graphicsFamily,
						hostQueueCount,
					),
					"SL proxy activation must succeed against the merged queue layout",
				)

				val config = DlssStartupConfig(
					enabled = true,
					qualityMode = SRMode.QUALITY,
					outputDimensions = Dimensions(2560, 1440),
					// The sdk path is a compatibility input the retired direct-NGX path used to
					// consume; initialize validates it and records only the Vulkan tuple.
					sdkPath = dataPath,
					nativeLibraryPath = library,
					dataPath = dataPath,
					warnings = emptyList(),
				)
				val session = DlssSession(config)
				val dimensions = LifecycleAdapter(session, native).initialize(
					vulkan.instanceAddress(),
					vulkan.physicalDeviceAddress(),
					vulkan.deviceAddress(),
					dataPath,
					dataPath,
				)
				assertNotNull(dimensions, session.failure?.diagnostic())
				assertEquals(DlssSessionState.READY, session.state)
				assertTrue(dimensions!!.width in 1..2560)
				assertTrue(dimensions.height in 1..1440)
			}
		}
	}

	/**
	 * The family the fixture creates its queues in, discovered with a throwaway default fixture
	 * so the augmented fixture can be built with the extras keyed by the right family.
	 */
	private fun probeGraphicsQueueFamily(): Int =
		HeadlessVulkanFixture().use { it.graphicsQueueFamilyIndex() }
}
