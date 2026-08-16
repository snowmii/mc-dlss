package me.snowmii.dlss.render
import me.snowmii.dlss.bridge.ExtensionBootstrap
import me.snowmii.streamline.ImageBinding
import me.snowmii.streamline.MotionRequest
import me.snowmii.streamline.EvaluationImages
import me.snowmii.dlss.bridge.Native
import me.snowmii.dlss.bridge.NativeApi
import me.snowmii.dlss.bridge.HeadlessVulkanFixture
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.SRMode
import me.snowmii.streamline.Dimensions
import me.snowmii.dlss.session.LifecycleAdapter

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import me.snowmii.dlss.NativeBridge
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Proves the native bridge actually fills its motion image, on the caller's command buffer, from
 * the engine's depth image and the frame's reprojection.
 *
 * Until this point the motion image was allocated, transitioned, and handed to NGX with nothing
 * ever written into it: DLSS was reading uninitialised memory as camera motion. [DlssCameraMotion]
 * already derives the reprojection - one matrix that maps a jittered clip position to the previous
 * frame's unjittered one - and this is the GPU work that turns it into a per-pixel image.
 *
 * The assertions are readback, not inspection, because a compute dispatch that binds the wrong
 * descriptor or reads the wrong texel still records, submits, and completes without complaint. The
 * identity case is the coherence check the whole motion design rests on - a still camera must
 * produce exactly zero at every depth - and the depth-dependent case is what separates a shader
 * that reads the depth buffer from one that ignores it and happens to look right.
 */
@NativeBridge
class DlssMotionVectorTest {
	private val output = Dimensions(2560, 1440)

	@Test
	fun `motion pass fills the motion image from depth and reprojection`(@TempDir dataPath: Path) {
		val library = nativeLibrary()
		val instanceExtensions = ExtensionBootstrap.queryInstanceExtensions()
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
				val extensions = mutableListOf<String>()
				ExtensionBootstrap.addDeviceExtensions(extensions, vkInstance, vkPhysicalDevice)
				extensions
			},
			true,
			mapOf(graphicsFamily to extras),
		).use { vulkan ->
			assertTrue(
				vulkan.validationEnabled(),
				"VK_LAYER_KHRONOS_validation must be installed; without it this test proves nothing about layouts",
			)

			Native.open(library).use { native ->
				// Bootstrap is idempotent across bridge instances: the runtime is already up.
				assertEquals(
					NativeApi.SUCCESS_RESULT,
					native.bootstrapStreamline(ExtensionBootstrap.streamlineRuntimeDirectory()),
				)
				// The fixture creates one host queue in the family, so Streamline's own queues
				// start at index 1 - right after the host's, as slSetVulkanInfo records them.
				// The optimal-dimension query answers from Streamline, so the device has to be
				// recorded with it before startup can complete.
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
				val session = DlssSession(
					DlssStartupConfig(
						enabled = true,
						qualityMode = SRMode.QUALITY,
						outputDimensions = output,
						sdkPath = dataPath,
						nativeLibraryPath = library,
						dataPath = dataPath,
						warnings = emptyList(),
					),
				)
				val adapter = LifecycleAdapter(session, native)
				val render = adapter.initialize(
					vulkan.instanceAddress(),
					vulkan.physicalDeviceAddress(),
					vulkan.deviceAddress(),
					dataPath,
					dataPath,
				)
				assertNotNull(render, session.failure?.diagnostic())
				val images = adapter.acquireImages()
				assertNotNull(images, session.failure?.diagnostic())

				// Stand-in for the scene target's depth attachment: render-sized, created and left
				// in GENERAL exactly as VulkanGpuTexture leaves Minecraft's own textures.
				val depth = vulkan.createEngineImage(
					render!!.width,
					render.height,
					VK_FORMAT_D32_SFLOAT,
					VK_IMAGE_USAGE_SAMPLED_BIT or VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT or
						VK_IMAGE_USAGE_TRANSFER_DST_BIT,
					VK_IMAGE_ASPECT_DEPTH_BIT,
				)

				// A still camera: the reprojection is the identity, so every pixel maps to itself
				// however deep it is. This is the one case the whole motion design has to get
				// exactly right, because any drift here is accumulated by DLSS as camera movement.
				var motion = recordMotion(vulkan, native, depth, images!!, render, Matrix4f(), DEPTH_NEAR)
				assertUniform(motion, 0f, 0f, "a still camera must produce zero motion at every pixel")

				// A camera that moved: a pure clip-space translation reprojects every pixel by the
				// same normalized-device offset, whatever its depth.
				motion = recordMotion(
					vulkan,
					native,
					depth,
					images,
					render,
					Matrix4f().translation(0.25f, -0.5f, 0f),
					DEPTH_NEAR,
				)
				assertUniform(motion, 0.25f, -0.5f, "a translated camera must produce exactly its own offset")

				// Depth is genuinely read: this reprojection's x output is a multiple of the clip z,
				// so the stored vector is only right if the shader fetched the depth buffer. Cleared
				// to 0.75 with a 0.5 coefficient, every pixel must read 0.375 in x.
				motion = recordMotion(
					vulkan,
					native,
					depth,
					images,
					render,
					Matrix4f().m20(0.5f),
					DEPTH_FAR,
				)
				assertUniform(motion, 0.375f, 0f, "the motion vector must depend on the depth buffer")

				// The restoration claim: this barrier is only legal if the pass handed the depth
				// image back in GENERAL, and validation contradicts it otherwise.
				val after = vulkan.allocateAndBeginCommandBuffer()
				vulkan.recordGeneralLayoutBarrier(after, depth)
				vulkan.endSubmitAndWait(after)
				assertEquals(
					emptyList<String>(),
					vulkan.validationErrorsAbout(depth.image(), images.motion.image),
					"no validation error may name the depth or motion image",
				)

				// Dimensions that disagree with the configuration name an image that does not exist
				// at that size, so the call fails rather than dispatching against the wrong extent.
				val mismatched = vulkan.allocateAndBeginCommandBuffer()
				assertNotEquals(
					NativeApi.SUCCESS_RESULT,
					native.writeMotion(
						MotionRequest(
							mismatched.address(),
							ImageBinding(depth.view(), depth.image(), depth.format()),
							FloatArray(16).also { Matrix4f().get(it) },
							Dimensions(render.width + 1, render.height),
						),
					),
				)
				vulkan.endSubmitAndWait(mismatched)

				// Once the images are gone there is nothing to write into, and the pass must say so
				// rather than dispatch against a destroyed view.
				assertTrue(adapter.releaseImages())
				val released = vulkan.allocateAndBeginCommandBuffer()
				assertNotEquals(
					NativeApi.SUCCESS_RESULT,
					native.writeMotion(
						MotionRequest(
							released.address(),
							ImageBinding(depth.view(), depth.image(), depth.format()),
							FloatArray(16).also { Matrix4f().get(it) },
							Dimensions(render.width, render.height),
						),
					),
				)
				vulkan.endSubmitAndWait(released)
			}
		}
	}

	/**
	 * Clears the depth image to [depthValue], records the motion pass against [reprojection] on the
	 * same command buffer, submits it, and reads the motion image back as interleaved x/y floats.
	 */
	private fun recordMotion(
		vulkan: HeadlessVulkanFixture,
		native: Native,
		depth: HeadlessVulkanFixture.EngineImage,
		images: EvaluationImages,
		render: Dimensions,
		reprojection: Matrix4f,
		depthValue: Float,
	): FloatArray {
		val commandBuffer = vulkan.allocateAndBeginCommandBuffer()
		vulkan.recordDepthClear(commandBuffer, depth, depthValue)
		assertEquals(
			NativeApi.SUCCESS_RESULT,
			native.writeMotion(
				MotionRequest(
					commandBuffer.address(),
					ImageBinding(depth.view(), depth.image(), depth.format()),
					FloatArray(16).also { reprojection.get(it) },
					Dimensions(render.width, render.height),
				),
			),
		)
		vulkan.endSubmitAndWait(commandBuffer)
		return vulkan.readRg16fImage(images.motion.image, render.width, render.height)
	}

	/** Asserts every pixel of an interleaved x/y readback carries the same expected vector. */
	private fun assertUniform(motion: FloatArray, expectedX: Float, expectedY: Float, message: String) {
		for (index in motion.indices step 2) {
			val x = motion[index]
			val y = motion[index + 1]
			if (abs(x - expectedX) > TOLERANCE || abs(y - expectedY) > TOLERANCE) {
				val pixel = index / 2
				throw AssertionError("$message; pixel $pixel holds ($x, $y), expected ($expectedX, $expectedY)")
			}
		}
	}

	private fun nativeLibrary(): Path {
		val library = Path.of("").toAbsolutePath().resolve("build/native/mc_dlss.dll")
		assertTrue(Files.isRegularFile(library), "buildNativeDlss must produce mc_dlss.dll")
		return library
	}

	/**
	 * The family the fixture creates its queues in, discovered with a throwaway default fixture
	 * so the augmented fixture can be built with the extras keyed by the right family.
	 */
	private fun probeGraphicsQueueFamily(): Int =
		HeadlessVulkanFixture().use { it.graphicsQueueFamilyIndex() }

	private companion object {
		/** Raw `VkFormat`, `VkImageUsageFlagBits`, and `VkImageAspectFlagBits` values. */
		const val VK_FORMAT_D32_SFLOAT = 126
		const val VK_IMAGE_USAGE_TRANSFER_DST_BIT = 0x2
		const val VK_IMAGE_USAGE_SAMPLED_BIT = 0x4
		const val VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT = 0x20
		const val VK_IMAGE_ASPECT_DEPTH_BIT = 0x2

		// Reversed-Z: 1.0 is the near plane in Minecraft 26.2 and 0.0 the far one. Both values are
		// exactly representable, and so is every expected result derived from them.
		const val DEPTH_NEAR = 1.0f
		const val DEPTH_FAR = 0.75f

		// The motion image is R16G16_SFLOAT. Every expectation here is exact in half precision, so
		// the tolerance only absorbs the shader's own arithmetic.
		const val TOLERANCE = 1e-3f
	}
}
