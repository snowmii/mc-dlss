package me.snowmii.dlss.sl

import java.nio.file.Path
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.bridge.DlssEvaluationImages
import me.snowmii.dlss.bridge.EvaluationRequest
import me.snowmii.dlss.bridge.ExtensionBootstrap
import me.snowmii.dlss.bridge.HeadlessVulkanFixture
import me.snowmii.dlss.bridge.ImageBinding
import me.snowmii.dlss.bridge.Native
import me.snowmii.dlss.bridge.NativeApi
import me.snowmii.dlss.bridge.Vec2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * The live signed Streamline session the M-3 SR rungs share: bootstrap, proxy activation, and
 * the initialize surface, against a headless device holding the merged queue layout.
 *
 * The bridge lifetimes mirror production: a query bridge (bootstrap + requirements) closes
 * before the device exists, an activation bridge (bootstrap + proxy activation) closes before
 * initialize, and a separate runtime bridge initializes and runs the rung's work. The native
 * module is pinned for the JVM lifetime, so the Streamline bootstrap state and the proxy
 * tuple recorded by the closed bridges survive their closes and are exactly what the runtime
 * bridge's initialize requires - a lookup tied to a bridge's arena would unload the module
 * with the activation bridge and initialize would be refused for a lost proxy tuple.
 *
 * The fixture OUTLIVES the runtime bridge: Native.close runs mc_dlss_close, which destroys
 * the module's images and motion pass and then shuts the Streamline runtime down - all while
 * the Vulkan device is still alive. That ordering is the lifecycle contract: Streamline's
 * plugins keep their worker threads running until slShutdown, and shutting the runtime down
 * before the device dies is what keeps the fork's JVM exit from crashing in sl.common.dll or
 * nvcuda64.dll. The queue requirements come from the query bridge, closed before the device
 * exists, when its close path is a no-op.
 *
 * The whole device-backed scenario for a rung runs in ONE test method (and therefore one test
 * fork): the fork's close-path slShutdown is what makes its exit clean, and a fork that
 * followed an unclean exit comes up with the plugin manager already initialized, which makes
 * slSetVulkanInfo answer eErrorInvalidIntegration. Splitting a scenario across two forks
 * makes the second fork's activation fail on this workstation no matter what it does.
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
		val requirements = Native.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
			assertEquals(
				NativeApi.SUCCESS_RESULT,
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
			Native.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
				)
				// The fixture creates one host queue in the family, so Streamline's own queues
				// start at index 1 - right after the host's, as slSetVulkanInfo records them.
				val hostQueueCount = 1
				assertEquals(
					NativeApi.SUCCESS_RESULT,
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
			// records it for the module-owned images and motion pass. The sdk/data paths are
			// compatibility inputs the retired direct-NGX path used to consume; the temp
			// directory stands in for them.
			Native.open(ExtensionBootstrap.nativeLibrary()).use { bridge ->
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					bridge.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
				)
				assertEquals(
					NativeApi.SUCCESS_RESULT,
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
	 * Records the already-activated Vulkan tuple through the existing mc_dlss_initialize so the
	 * bridge's close path shuts the Streamline runtime down while the fixture's device is still
	 * alive.
	 *
	 * The activation-only live forks (options, requirements-merge, proxy-activation) activate
	 * the proxies but run no session work, so their close was a no-op and the fork exited with
	 * the runtime up and the device gone - the sl.common.dll / nvcuda64.dll exit-crash family.
	 * Calling this after the fixture's assertions arms the close path: initialize validates the
	 * tuple against the recorded proxy tuple and marks the session ready, and the bridge's
	 * close (inside the fixture's scope) then runs the orderly slShutdown before the device is
	 * destroyed. The sdk/data paths are the same compatibility inputs every live session
	 * passes; only well-formedness is checked.
	 */
	fun recordActivatedSession(
		bridge: Native,
		fixture: HeadlessVulkanFixture,
		dataPath: Path,
	) {
		assertEquals(
			NativeApi.SUCCESS_RESULT,
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
	 * Asserts the canonical rung's validation oracle was actually running, then that the
	 * Khronos validation layer reported no errors naming any of the frame's four images (the
	 * engine's two and the module's two).
	 *
	 * Validation is the only oracle for image layouts: a wrong oldLayout is undefined
	 * behaviour to the driver and silent without it, so a session whose layer could not be
	 * enabled has no evidence worth asserting on and the rung FAILS rather than silently
	 * skipping the clean check.
	 */
	fun assertValidationClean(
		fixture: HeadlessVulkanFixture,
		color: HeadlessVulkanFixture.EngineImage,
		depth: HeadlessVulkanFixture.EngineImage,
		images: DlssEvaluationImages,
	) {
		assertTrue(
			fixture.validationEnabled(),
			"the canonical rung needs the Khronos validation layer (VK_LAYER_KHRONOS_validation " +
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
		dimensions: DlssDimensions,
		reset: Boolean,
	): EvaluationRequest = EvaluationRequest(
		commandBuffer = commandBuffer,
		color = ImageBinding(color.view(), color.image(), color.format()),
		depth = ImageBinding(depth.view(), depth.image(), depth.format()),
		// The offset is in render pixels, the unit the jitter sequence is in. The motion
		// buffer is normalized device units, so the scale that normalizes it onto [-1,1] is one.
		jitter = Vec2(0.25f, -0.5f),
		motionScale = Vec2(1f, 1f),
		frameTimeMilliseconds = 16.6f,
		resetHistory = reset,
		renderDimensions = dimensions,
	)

	/**
	 * The family the fixture creates its queues in, discovered with a throwaway default fixture
	 * so the augmented fixture can be built with the extras keyed by the right family.
	 */
	fun probeGraphicsQueueFamily(): Int =
		HeadlessVulkanFixture().use { it.graphicsQueueFamilyIndex() }
}
