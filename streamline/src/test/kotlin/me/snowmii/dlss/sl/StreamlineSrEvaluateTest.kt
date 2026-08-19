package me.snowmii.dlss.sl

import java.nio.file.Path
import me.snowmii.dlss.NativeBridge
import me.snowmii.streamline.ImageBinding
import me.snowmii.streamline.StreamlineSession
import me.snowmii.streamline.SrTagRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.lwjgl.vulkan.VK10

/**
 * DLSS SR evaluates through Streamline on the caller's command buffer.
 *
 * The live frame drives the whole SL path on a headless device: bootstrap, proxy activation,
 * initialize, optimal-dimension query, configure, module-image acquisition, then per frame
 * engine colour/depth tag (slGetNewFrameToken + slSetTagForFrame) and evaluation
 * (slSetConstants + slEvaluateFeature) on one allocated command buffer that must submit
 * clean under the Khronos validation layer. The plugin transitions tagged resources from the
 * declared states; a stale declaration surfaces there.
 *
 * The live scenario lives in [SrLiveSession], shared with [SrOnStreamlineTest]. This class
 * asserts the evaluate seam and the frame ordering around it. One test method (one fork):
 * close-path slShutdown makes the fork's exit clean; an unclean exit leaves the plugin
 * manager initialized, so the next fork's slSetVulkanInfo answers eErrorInvalidIntegration.
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
				StreamlineSession.SUCCESS_RESULT,
				bridge.tagSrResources(SrTagRequest(frame.address(), tagRequest.color, tagRequest.depth)),
				"the frame's resources must tag on the caller's command buffer",
			)
			assertEquals(
				StreamlineSession.SUCCESS_RESULT,
				bridge.evaluateSuperResolution(SrLiveSession.evaluationRequest(frame.address(), color, depth, dimensions, reset = true)),
				"the evaluation must record on the tagged frame's buffer",
			)
			fixture.endSubmitAndWait(frame)
			SrLiveSession.assertFrameValidationClean(fixture, color, depth, images!!)

			// Frame two: the same images with accumulated history rather than a reset, starting
			// from the layouts the first frame left behind.
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
}
