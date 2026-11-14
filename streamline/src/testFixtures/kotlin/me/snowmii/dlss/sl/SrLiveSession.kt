package me.snowmii.dlss.sl

import java.nio.file.Path
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.EvaluationImages
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.streamline.ExtensionBootstrap
import me.snowmii.dlss.bridge.HeadlessVulkanFixture
import me.snowmii.dlss.api.ImageBinding
import me.snowmii.streamline.Native
import me.snowmii.streamline.NativeTestAccess
import me.snowmii.streamline.StreamlineSession
import me.snowmii.streamline.Vec2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

object SrLiveSession {

	fun withLiveSession(dataPath: Path, block: (Native, HeadlessVulkanFixture) -> Unit) {
		val instanceExtensions = ExtensionBootstrap.queryInstanceExtensions()
		val requirements = NativeTestAccess.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
			assertEquals(
				StreamlineSession.SUCCESS_RESULT,
				bridge.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
			)
			bridge.queryQueueRequirements()
		}

		val graphicsFamily = probeGraphicsQueueFamily()
		val extras = requirements.graphicsQueues + requirements.computeQueues
		HeadlessVulkanFixture(
			instanceExtensions,
			{ instance, physicalDevice ->
				val extensions = mutableListOf<String>()
				ExtensionBootstrap.addDeviceExtensions(extensions, instance, physicalDevice)
				extensions
			},
			true,
			mapOf(graphicsFamily to extras),
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
			}

			NativeTestAccess.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
				assertEquals(
					StreamlineSession.SUCCESS_RESULT,
					bridge.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
				)
				assertEquals(
					StreamlineSession.SUCCESS_RESULT,
					bridge.initialize(
						fixture.instanceAddress(),
						fixture.physicalDeviceAddress(),
						fixture.deviceAddress(),
						dataPath,
						dataPath,
					),
					"initialize must record the activated Vulkan tuple",
				)
				block(bridge, fixture)
			}
		}
	}

	fun recordActivatedSession(
		bridge: Native,
		fixture: HeadlessVulkanFixture,
		dataPath: Path,
	) {
		assertEquals(
			StreamlineSession.SUCCESS_RESULT,
			bridge.initialize(
				fixture.instanceAddress(),
				fixture.physicalDeviceAddress(),
				fixture.deviceAddress(),
				dataPath,
				dataPath,
			),
			"initialize must record the activated tuple so close can shut Streamline down",
		)
	}

	fun assertFrameValidationClean(
		fixture: HeadlessVulkanFixture,
		color: HeadlessVulkanFixture.EngineImage,
		depth: HeadlessVulkanFixture.EngineImage,
		images: EvaluationImages,
	) {
		assertTrue(
			fixture.validationEnabled(),
			"this test needs the Khronos validation layer (VK_LAYER_KHRONOS_validation " +
				"plus VK_EXT_debug_utils); without it the clean-frame assertion is worthless",
		)
		val errors = fixture.validationErrorsAbout(
			color.image(),
			depth.image(),
			images.motion.image,
			images.output.image,
		)
		assertTrue(
			errors.isEmpty(),
			"the evaluated frame must not leave a resource in a state validation rejects: $errors",
		)
	}

	fun evaluationRequest(
		commandBuffer: Long,
		color: HeadlessVulkanFixture.EngineImage,
		depth: HeadlessVulkanFixture.EngineImage,
		dimensions: Dimensions,
		reset: Boolean,
	): EvaluationRequest = EvaluationRequest.builder()
		.commandBuffer(commandBuffer)
		.color(ImageBinding(color.view(), color.image(), color.format()))
		.depth(ImageBinding(depth.view(), depth.image(), depth.format()))
		.jitter(Vec2(0.25f, -0.5f))
		.motionScale(Vec2(1f, 1f))
		.frameTimeMilliseconds(16.6f)
		.resetHistory(reset)
		.renderDimensions(dimensions)
		.build()

	fun probeGraphicsQueueFamily(): Int =
		HeadlessVulkanFixture().use { it.graphicsQueueFamilyIndex() }
}
