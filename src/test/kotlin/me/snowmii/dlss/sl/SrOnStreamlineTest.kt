package me.snowmii.dlss.sl

import java.nio.file.Files
import java.nio.file.Path
import me.snowmii.dlss.NativeBridge
import me.snowmii.dlss.bridge.ExtensionBootstrap
import me.snowmii.streamline.ImageBinding
import me.snowmii.dlss.bridge.Native
import me.snowmii.dlss.bridge.NativeApi
import me.snowmii.dlss.bridge.NativeException
import me.snowmii.streamline.SrTagRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.io.TempDir
import org.lwjgl.vulkan.VK10

/**
 * M-3 canonical rung: DLSS Super Resolution runs entirely on the signed Streamline 2.12.0
 * stack, and the direct-NGX implementation is retired with no fallback.
 *
 * The live frame drives the whole SL path on a headless device: bootstrap, proxy activation,
 * initialize, optimal-dimension query, configure, module-image acquisition, and then per frame
 * the engine's colour and depth tag (slGetNewFrameToken + slSetTagForFrame) and the evaluation
 * (slSetConstants + slEvaluateFeature) on ONE allocated command buffer that must submit clean
 * under the Khronos validation layer - for two consecutive frames, the second one building on
 * the first frame's history.
 *
 * The source assertions close the retirement: no NVSDK_NGX_VULKAN init/query/create/evaluate/
 * release/shutdown call exists anywhere in the native code, no capability-parameter surface
 * remains, buildNativeDlss does not link nvsdk_ngx_s.lib, and mc_dlss_initialize only
 * validates and records the Vulkan tuple the mod already activated through slSetVulkanInfo.
 *
 * Methods are ORDERED because the fork is one process and the live session's close shuts the
 * Streamline runtime down (slShutdown, while the fixture device is still alive - the fix that
 * keeps the fork's JVM exit from crashing in sl.common.dll / nvcuda64.dll). On SL 2.12.0 a
 * bootstrap after that shutdown re-runs slInit but cannot serve the DLSS feature again in the
 * same process (slGetFeatureRequirements answers eErrorFeatureMissing), so the bootstrap-
 * dependent methods must run before the live session, and nothing bootstraps after it.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@NativeBridge
class SrOnStreamlineTest {

	@Order(5)
	@Test
	fun `signed Streamline drives two validation-clean SR frames and no second NGX owner`(
		@TempDir dataPath: Path,
	) {
		SrLiveSession.withLiveSession(dataPath) { bridge, fixture ->

			val outputWidth = 2560
			val outputHeight = 1440
			// MaxQuality = 2 (NVSDK_NGX_PerfQuality_Value), which the bridge maps onto
			// sl::DLSSMode::eMaxQuality; preset K = 11 lands on the qualityPreset field.
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
				NativeApi.SUCCESS_RESULT,
				bridge.configure(
					outputWidth,
					outputHeight,
					dimensions.width,
					dimensions.height,
					2,
					11,
				),
				"configure must record the SL options for the stored configuration",
			)

			// The evaluation reads the module's motion image and writes the output image, so
			// both have to exist at the configured sizes before the first evaluate.
			val images = bridge.acquireImages()

			assertNotNull(images, "module images must be acquired before the evaluation")

			// The engine's render-sized colour and depth, standing in for Minecraft's main
			// target and depth texture; the fixture leaves them in VK_IMAGE_LAYOUT_GENERAL,
			// which is where Minecraft rests its own textures.
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
				VK10.VK_IMAGE_USAGE_SAMPLED_BIT or VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,
				VK10.VK_IMAGE_ASPECT_DEPTH_BIT,
			)
			val tagRequest = SrTagRequest(
				0,
				ImageBinding(color.view(), color.image(), VK10.VK_FORMAT_R8G8B8A8_UNORM),
				ImageBinding(depth.view(), depth.image(), VK10.VK_FORMAT_D32_SFLOAT),
			)

			// Frame one: the frame's resources tag and then evaluate on ONE buffer, the way
			// FrameEvaluation records them. The evaluation must succeed and the buffer must
			// submit clean.
			val frame = fixture.allocateAndBeginCommandBuffer()
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				bridge.tagSrResources(SrTagRequest(frame.address(), tagRequest.color, tagRequest.depth)),
				"the frame's resources must tag on the caller's command buffer",
			)
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				bridge.evaluate(
					SrLiveSession.evaluationRequest(frame.address(), color, depth, dimensions, reset = true),
				),
				"the evaluation must record on the tagged frame's buffer",
			)
			fixture.endSubmitAndWait(frame)
			SrLiveSession.assertValidationClean(fixture, color, depth, images!!)

			// Frame two: the same images with accumulated history rather than a reset, starting
			// from the layouts the first frame left behind.
			val secondFrame = fixture.allocateAndBeginCommandBuffer()
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				bridge.tagSrResources(SrTagRequest(secondFrame.address(), tagRequest.color, tagRequest.depth)),
				"the second frame must tag with a fresh frame token",
			)
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				bridge.evaluate(
					SrLiveSession.evaluationRequest(secondFrame.address(), color, depth, dimensions, reset = false),
				),
				"the second frame must evaluate from the layouts the first left behind",
			)
			fixture.endSubmitAndWait(secondFrame)
			SrLiveSession.assertValidationClean(fixture, color, depth, images)
		}
	}

	@Order(1)
	@Test
	fun `close shuts Streamline down after module resources and resets the bootstrap bookkeeping`() {
		val sessionSource = Files.readString(Path.of("native", "internal", "session.cpp"))
		val slSource = Files.readString(Path.of("native", "internal", "sl_dlss.cpp"))

		// The teardown order is the lifecycle contract: the module's own GPU objects die
		// first, the Streamline runtime shuts down while the caller's device is still alive,
		// and the state reset comes last so the bootstrap/proxy/session bookkeeping is
		// coherent: a later bootstrap re-runs slInit instead of treating the shutdown runtime
		// as already up.
		val shutdown = sessionSource.substringAfter("shutdown_state").substringBefore("kSuccess")
		val timing = shutdown.indexOf("destroy_timing()")
		val motion = shutdown.indexOf("destroy_motion_pass()")
		val images = shutdown.indexOf("release_images()")
		val streamline = shutdown.indexOf("shutdown_streamline()")
		val reset = shutdown.indexOf("reset_state()")
		listOf(timing, motion, images, streamline, reset).forEach { index ->
			assertTrue(index >= 0, "shutdown_state must contain every teardown stage in order")
		}
		assertTrue(
			timing < motion && motion < images && images < streamline && streamline < reset,
			"module resources must release before the Streamline shutdown, and reset must come last",
		)

		// The sl:: runtime call stays inside the Streamline unit; the ordering unit only names
		// it, and the shutdown runs only when the runtime is actually up.
		assertTrue(!sessionSource.contains("slShutdown("), "the sl:: call belongs in the SL unit")
		assertTrue(slSource.contains("slShutdown()"), "shutdown_streamline must call slShutdown")
		assertTrue(
			slSource.substringAfter("shutdown_streamline").contains("g_state.streamlineInitialized"),
			"shutdown_streamline must only run when the runtime is up",
		)
	}

	@Order(2)
	@Test
	fun `native code contains no direct NGX runtime calls and the build links no NGX library`() {
		// Every native translation unit and header, walked rather than enumerated so a new
		// unit cannot silently reintroduce the retired path.
		val nativeFiles = Files.walk(Path.of("native"))
			.filter { Files.isRegularFile(it) }
			.filter { it.toString().endsWith(".cpp") || it.toString().endsWith(".h") }
			.toList()

		// The blanket prefixes: NVSDK_NGX_VULKAN_ is the init/query/create/evaluate/release/
		// shutdown surface, NVSDK_NGX_Parameter_ the capability-parameter surface. The
		// reference-vocabulary constants (results, PerfQuality values, presets) live in
		// nvsdk_ngx.h, not under either prefix.
		listOf("NVSDK_NGX_VULKAN_", "NVSDK_NGX_Parameter_").forEach { prefix ->
			val offenders = nativeFiles.filter { Files.readString(it).contains(prefix) }
			assertTrue(
				offenders.isEmpty(),
				"the direct-NGX runtime surface must be gone from native code: $prefix in $offenders",
			)
		}

		// The build links Streamline and the Vulkan loader and nothing from the NGX SDK: the
		// reference-only 310.7.0 headers stay on the include path, the library never reaches
		// the link line.
		val buildScript = Files.readString(Path.of("build.gradle.kts"))
		assertTrue(
			!buildScript.contains("nvsdk_ngx_s.lib"),
			"buildNativeDlss must not link nvsdk_ngx_s.lib",
		)
		assertTrue(
			buildScript.contains("lib/x64/sl.interposer.lib"),
			"buildNativeDlss must still link sl.interposer.lib",
		)
	}

	@Order(4)
	@Test
	fun `pinned module lookup survives bridge close and initialize gates on proxy activation`(
		@TempDir dataPath: Path,
	) {
		val library = ExtensionBootstrap.nativeLibrary()
		val runtime = ExtensionBootstrap.streamlineRuntimeDirectory()

		// Runs before the live session (Order 4 of 5): the live session's close shuts the
		// Streamline runtime down, and this method's bootstrap and queries need the runtime
		// up. The close here is a no-op while no session is ready (mc_dlss_close returns
		// success without tearing anything down), which is exactly what this method pins.

		// Bridge one bootstraps the shared module and then closes. The close is a no-op while
		// no session is ready (mc_dlss_close returns success without tearing anything down),
		// and the pinned lookup keeps the module loaded: the Streamline bootstrap state lives
		// in the module's globals, not in the bridge's arena.
		Native.open(library).use { bridge ->
			assertEquals(NativeApi.SUCCESS_RESULT, bridge.bootstrapStreamline(runtime))
			assertTrue(bridge.queryQueueRequirements().graphicsQueues >= 0)
		}

		// Bridge two opens WITHOUT bootstrapping: the requirements query still answers only if
		// the module globals survived bridge one's close. A lookup tied to the bridge's arena
		// would have unloaded the module and the query would fail with kNotInitialized.
		Native.open(library).use { bridge ->
			assertTrue(
				bridge.queryQueueRequirements().graphicsQueues >= 0,
				"the pinned module must keep its Streamline bootstrap state across a bridge close",
			)

			// The activation gate: initialize before proxy activation fails, and the failed
			// call records nothing - a second identical initialize fails the same way (a
			// recorded sessionReady would answer success on the repeat).
			val first = bridge.initialize(1L, 2L, 3L, dataPath, dataPath)
			assertTrue(
				first != NativeApi.SUCCESS_RESULT,
				"initialize without proxy activation must fail, got $first",
			)
			assertEquals(
				first,
				bridge.initialize(1L, 2L, 3L, dataPath, dataPath),
				"a failed initialize must not record the tuple",
			)
			assertThrows(
				NativeException::class.java,
			) { bridge.acquireImages() }
		}
	}
}
