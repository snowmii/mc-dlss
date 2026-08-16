package me.snowmii.dlss.sl

import java.nio.file.Path
import me.snowmii.dlss.NativeBridge
import me.snowmii.dlss.bridge.ImageBinding
import me.snowmii.dlss.bridge.NativeApi
import me.snowmii.dlss.bridge.SrTagRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.lwjgl.vulkan.VK10

/**
 * M-3 rung: DLSS SR evaluates through Streamline on the caller's command buffer.
 *
 * The live frame drives the whole SL path on a headless device: bootstrap, proxy activation,
 * initialize, optimal-dimension query, configure, module-image acquisition, and then per frame
 * the engine's colour and depth tag (slGetNewFrameToken + slSetTagForFrame) and the evaluation
 * (slSetConstants + slEvaluateFeature) on ONE allocated command buffer that must submit clean
 * under the Khronos validation layer - the plugin transitions the tagged resources from the
 * declared states, and a stale declaration would surface there.
 *
 * The live scenario lives in [SrLiveSession], which the canonical M-3 rung
 * ([SrOnStreamlineTest]) shares; this class asserts the evaluate seam itself and the frame
 * ordering around it. The whole device-backed scenario runs in ONE test method (and therefore
 * one test fork): the close-path slShutdown is what makes the fork's exit clean, and a fork
 * that followed an unclean exit comes up with the plugin manager already initialized, which
 * makes slSetVulkanInfo answer eErrorInvalidIntegration. Splitting the scenario across two
 * forks makes the second fork's activation fail on this workstation no matter what it does.
 */
@NativeBridge
class StreamlineSrEvaluateTest {

	@Test
	fun `SL SR evaluates on one clean command buffer after tagging the frame's resources`(
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
				commandBuffer = 0,
				color = ImageBinding(color.view(), color.image(), VK10.VK_FORMAT_R8G8B8A8_UNORM),
				depth = ImageBinding(depth.view(), depth.image(), VK10.VK_FORMAT_D32_SFLOAT),
			)

			// Frame one: the frame's resources tag and then evaluate on ONE buffer, the way
			// FrameEvaluation records them. The evaluation must succeed and the buffer must
			// submit clean.
			val frame = fixture.allocateAndBeginCommandBuffer()
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				bridge.tagSrResources(tagRequest.copy(commandBuffer = frame.address())),
				"the frame's resources must tag on the caller's command buffer",
			)
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				bridge.evaluate(SrLiveSession.evaluationRequest(frame.address(), color, depth, dimensions, reset = true)),
				"the evaluation must record on the tagged frame's buffer",
			)
			fixture.endSubmitAndWait(frame)
			SrLiveSession.assertValidationClean(fixture, color, depth, images!!)

			// Frame two: the same images with accumulated history rather than a reset, starting
			// from the layouts the first frame left behind.
			val secondFrame = fixture.allocateAndBeginCommandBuffer()
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				bridge.tagSrResources(tagRequest.copy(commandBuffer = secondFrame.address())),
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
}
