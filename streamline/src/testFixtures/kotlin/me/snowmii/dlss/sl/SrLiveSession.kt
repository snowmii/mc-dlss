package me.snowmii.dlss.sl

import java.nio.file.Path
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.EvaluationImages
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.streamline.ExtensionBootstrap
import me.snowmii.dlss.bridge.HeadlessVulkanFixture
import me.snowmii.streamline.ImageBinding
import me.snowmii.streamline.Native
import me.snowmii.streamline.NativeTestAccess
import me.snowmii.streamline.StreamlineSession
import me.snowmii.streamline.Vec2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Shared live Streamline SR session: bootstrap, proxy activation, and initialize against a
 * headless device holding the merged queue layout.
 *
 * Bridge lifetimes match production: a query bridge closes before the device exists, an
 * activation bridge closes before initialize, and a runtime bridge runs the scenario. The
 * native module is pinned for the JVM lifetime, so bootstrap state and the proxy tuple
 * survive those closes; an arena-scoped lookup would unload the module and initialize would
 * refuse a lost proxy tuple.
 *
 * The fixture outlives the runtime bridge: `mc_dlss_close` destroys module GPU objects and
 * shuts Streamline down while the Vulkan device is still alive. Streamline plugins keep
 * worker threads until slShutdown; shutting the runtime down after the device dies crashes
 * the fork in sl.common.dll / nvcuda64.dll. Queue requirements come from the query bridge,
 * whose close is a no-op before a session is ready.
 *
 * Each device-backed scenario stays in one test method (one fork). An unclean exit leaves
 * the plugin manager initialized, so the next fork's slSetVulkanInfo answers
 * eErrorInvalidIntegration.
 */
object SrLiveSession {

	/**
	 * Bootstraps Streamline, activates the Vulkan proxies against a headless device holding the
	 * merged queue layout, runs the initialize surface production runs, then executes [block]
	 * with the live runtime bridge and fixture.
	 */
	fun withLiveSession(dataPath: Path, block: (Native, HeadlessVulkanFixture) -> Unit) {
		// Query bridge: instance requirements and the merged queue counts, closed before the
		// device exists (its close path is a no-op while no session is ready).
		val instanceExtensions = ExtensionBootstrap.queryInstanceExtensions()
		val requirements = NativeTestAccess.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
			assertEquals(
				StreamlineSession.SUCCESS_RESULT,
				bridge.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
			)
			bridge.queryQueueRequirements()
		}

		// The production merge starts from Minecraft's {graphicsFamily: 1} queue map and
		// adds SL's extra graphics and compute queues; the first graphics family is
		// compute-capable on this workstation, so both merges land in the same family.
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
			// Activation bridge: bootstrap (idempotent on the shared module) and the proxy
			// activation, closed before initialize exactly like ExtensionBootstrap's
			// activateVulkanProxies seam. The proxy tuple survives this close only because
			// the module is pinned; an arena-scoped lookup would unload it here.
			NativeTestAccess.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
				assertEquals(
					StreamlineSession.SUCCESS_RESULT,
					bridge.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
				)
				// The fixture creates one host queue in the family, so Streamline's own queues
				// start at index 1 - right after the host's, as slSetVulkanInfo records them.
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

			// Runtime bridge: bootstrap, then mc_dlss_initialize validates the tuple against
			// the proxy tuple the closed activation bridge recorded (pinned module) and
			// records it for the module-owned images and motion pass. sdkPath/dataPath are
			// unused; only well-formedness is checked.
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

	/**
	 * Records the already-activated Vulkan tuple through mc_dlss_initialize so the bridge's
	 * close runs slShutdown while the fixture's device is still alive.
	 *
	 * Activation-only forks never open a session, so close is otherwise a no-op and the
	 * process exits with the runtime up and the device gone. sdkPath/dataPath are unused;
	 * only well-formedness is checked.
	 */
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

	/**
	 * Asserts the Khronos validation layer is running and reported no errors naming the
	 * frame's four images (engine colour/depth, module motion/output).
	 *
	 * Validation is the only oracle for image layouts: a wrong oldLayout is undefined
	 * behaviour to the driver and silent without it. A session whose layer could not be
	 * enabled has no evidence worth asserting, so the check fails instead of skipping.
	 */
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

	/**
	 * The engine's two images and nothing else, with the render dimensions stamped the way the
	 * adapter stamps them in production. The motion and output images are the bridge's own and
	 * are reached natively.
	 */
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
		// The offset is in render pixels, the unit the jitter sequence is in. The motion
		// buffer is normalized device units, so the scale that normalizes it onto [-1,1] is one.
		.jitter(Vec2(0.25f, -0.5f))
		.motionScale(Vec2(1f, 1f))
		.frameTimeMilliseconds(16.6f)
		.resetHistory(reset)
		.renderDimensions(dimensions)
		.build()

	/**
	 * The family the fixture creates its queues in, discovered with a throwaway default fixture
	 * so the augmented fixture can be built with the extras keyed by the right family.
	 */
	fun probeGraphicsQueueFamily(): Int =
		HeadlessVulkanFixture().use { it.graphicsQueueFamilyIndex() }
}
