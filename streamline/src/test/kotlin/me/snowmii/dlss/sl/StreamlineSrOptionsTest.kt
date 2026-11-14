package me.snowmii.dlss.sl

import java.nio.file.Path
import me.snowmii.dlss.NativeBridge
import me.snowmii.streamline.ExtensionBootstrap
import me.snowmii.dlss.bridge.HeadlessVulkanFixture
import me.snowmii.dlss.api.ImageBinding
import me.snowmii.streamline.Native
import me.snowmii.streamline.NativeTestAccess
import me.snowmii.streamline.StreamlineSession
import me.snowmii.streamline.SrTagRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.lwjgl.vulkan.VK10

@NativeBridge
class StreamlineSrOptionsTest {

	@Test
	fun `SL options configure and SR resources tag on a live command buffer`(
		@TempDir dataPath: Path,
	) {
		withLiveSession(dataPath) { bridge, fixture ->
			val outputWidth = 2560
			val outputHeight = 1440
			val dimensions = bridge.queryOptimalDimensions(outputWidth, outputHeight, 2)
			assertTrue(
				dimensions.width in 1..outputWidth,
				"queried render width must be in (0, output], got ${dimensions.width}",
			)
			assertTrue(
				dimensions.height in 1..outputHeight,
				"queried render height must be in (0, output], got ${dimensions.height}",
			)
			assertEquals(
				StreamlineSession.SUCCESS_RESULT,
				bridge.configureSuperResolution(
					outputWidth,
					outputHeight,
					dimensions.width,
					dimensions.height,
					2,
					11,
				),
				"configure must record the SL options for the stored configuration",
			)

			val color = fixture.createEngineImage(
				dimensions.width,
				dimensions.height,
				VK10.VK_FORMAT_R8G8B8A8_UNORM,
				VK10.VK_IMAGE_USAGE_SAMPLED_BIT or VK10.VK_IMAGE_USAGE_STORAGE_BIT,
				VK10.VK_IMAGE_ASPECT_COLOR_BIT,
			)
			val depth = fixture.createEngineImage(
				dimensions.width,
				dimensions.height,
				VK10.VK_FORMAT_D32_SFLOAT,
				VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,
				VK10.VK_IMAGE_ASPECT_DEPTH_BIT,
			)
			val commandBuffer = fixture.allocateAndBeginCommandBuffer()

			assertEquals(
				StreamlineSession.SUCCESS_RESULT,
				bridge.tagSrResources(
					SrTagRequest(
						commandBuffer.address(),
						ImageBinding(
							color.view(),
							color.image(),
							VK10.VK_FORMAT_R8G8B8A8_UNORM,
						),
						ImageBinding(
							depth.view(),
							depth.image(),
							VK10.VK_FORMAT_D32_SFLOAT,
						),
					),
				),
				"the engine's colour and depth must tag on the caller's command buffer",
			)

			fixture.endSubmitAndWait(commandBuffer)
			if (fixture.validationEnabled()) {
				val errors = fixture.validationErrorsAbout(color.image(), depth.image())
				assertTrue(
					errors.isEmpty(),
					"tagging must not leave the engine's images in a state validation rejects: $errors",
				)
			}
		}
	}

	private fun withLiveSession(dataPath: Path, block: (Native, HeadlessVulkanFixture) -> Unit) {
		val instanceExtensions = ExtensionBootstrap.queryInstanceExtensions()

		val graphicsFamily = probeGraphicsQueueFamily()
		HeadlessVulkanFixture(
			instanceExtensions,
			{ instance, physicalDevice ->
				val extensions = mutableListOf<String>()
				ExtensionBootstrap.addDeviceExtensions(extensions, instance, physicalDevice)
				extensions
			},
			true,
			mapOf(graphicsFamily to requirementsExtras()),
		).use { fixture ->
			NativeTestAccess.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
				assertEquals(
					StreamlineSession.SUCCESS_RESULT,
					bridge.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
				)
				val hostQueueCount = 1
				assertEquals(
					StreamlineSession.SUCCESS_RESULT,
					bridge.activateVulkanProxies(
						fixture.instanceAddress(),
						fixture.physicalDeviceAddress(),
						fixture.deviceAddress(),
						graphicsFamily,
						hostQueueCount,
						graphicsFamily,
						hostQueueCount,
					),
					"activation must succeed against the merged queue layout",
				)
				block(bridge, fixture)
				SrLiveSession.recordActivatedSession(bridge, fixture, dataPath)
			}
		}
	}

	private fun requirementsExtras(): Int {
		val requirements = NativeTestAccess.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
			assertEquals(
				StreamlineSession.SUCCESS_RESULT,
				bridge.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
			)
			bridge.queryQueueRequirements()
		}
		return requirements.graphicsQueues + requirements.computeQueues
	}

	private fun probeGraphicsQueueFamily(): Int =
		HeadlessVulkanFixture().use { it.graphicsQueueFamilyIndex() }
}
