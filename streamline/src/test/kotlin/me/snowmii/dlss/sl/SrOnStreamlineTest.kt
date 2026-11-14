package me.snowmii.dlss.sl

import java.nio.file.Files
import java.nio.file.Path
import me.snowmii.dlss.NativeBridge
import me.snowmii.streamline.ExtensionBootstrap
import me.snowmii.dlss.api.ImageBinding
import me.snowmii.streamline.NativeTestAccess
import me.snowmii.streamline.StreamlineSession
import me.snowmii.streamline.StreamlineException
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

			val images = bridge.acquireImages()

			assertNotNull(images, "module images must be acquired before the evaluation")

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

			val frame = fixture.allocateAndBeginCommandBuffer()
			assertEquals(
				StreamlineSession.SUCCESS_RESULT,
				bridge.tagSrResources(SrTagRequest(frame.address(), tagRequest.color, tagRequest.depth)),
				"the frame's resources must tag on the caller's command buffer",
			)
			assertEquals(
				StreamlineSession.SUCCESS_RESULT,
				bridge.evaluateSuperResolution(
					SrLiveSession.evaluationRequest(frame.address(), color, depth, dimensions, reset = true),
				),
				"the evaluation must record on the tagged frame's buffer",
			)
			fixture.endSubmitAndWait(frame)
			SrLiveSession.assertFrameValidationClean(fixture, color, depth, images!!)
			bridge.presentStart()
			bridge.presentEnd()

			val secondFrame = fixture.allocateAndBeginCommandBuffer()
			assertEquals(
				StreamlineSession.SUCCESS_RESULT,
				bridge.tagSrResources(SrTagRequest(secondFrame.address(), tagRequest.color, tagRequest.depth)),
				"the second frame must tag with a fresh frame token",
			)
			assertEquals(
				StreamlineSession.SUCCESS_RESULT,
				bridge.evaluateSuperResolution(
					SrLiveSession.evaluationRequest(secondFrame.address(), color, depth, dimensions, reset = false),
				),
				"the second frame must evaluate from the layouts the first left behind",
			)
			fixture.endSubmitAndWait(secondFrame)
			SrLiveSession.assertFrameValidationClean(fixture, color, depth, images)
		}
	}

	@Order(1)
	@Test
	fun `close shuts Streamline down after module resources and resets the bootstrap bookkeeping`() {
		val sessionSource = Files.readString(Path.of("native", "internal", "session.cpp"))
		val slSource = Files.readString(Path.of("native", "internal", "sl_dlss.cpp"))

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
			motion in (timing + 1)..<images && images < streamline && streamline < reset,
			"module resources must release before the Streamline shutdown, and reset must come last",
		)

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
		val nativeFiles = Files.walk(Path.of("native"))
			.filter { Files.isRegularFile(it) }
			.filter { it.toString().endsWith(".cpp") || it.toString().endsWith(".h") }
			.toList()

		listOf("NVSDK_NGX_VULKAN_", "NVSDK_NGX_Parameter_").forEach { prefix ->
			val offenders = nativeFiles.filter { Files.readString(it).contains(prefix) }
			assertTrue(
				offenders.isEmpty(),
				"the direct-NGX runtime surface must be gone from native code: $prefix in $offenders",
			)
		}

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

		NativeTestAccess.open(library).use { bridge ->
			assertEquals(StreamlineSession.SUCCESS_RESULT, bridge.bootstrapStreamline(runtime))
			assertTrue(bridge.queryQueueRequirements().graphicsQueues >= 0)
		}

		NativeTestAccess.open(library).use { bridge ->
			assertTrue(
				bridge.queryQueueRequirements().graphicsQueues >= 0,
				"the pinned module must keep its Streamline bootstrap state across a bridge close",
			)

			val first = bridge.initialize(1L, 2L, 3L, dataPath, dataPath)
			assertTrue(
				first != StreamlineSession.SUCCESS_RESULT,
				"initialize without proxy activation must fail, got $first",
			)
			assertEquals(
				first,
				bridge.initialize(1L, 2L, 3L, dataPath, dataPath),
				"a failed initialize must not record the tuple",
			)
			assertThrows(
				StreamlineException::class.java,
			) { bridge.acquireImages() }
		}
	}
}
