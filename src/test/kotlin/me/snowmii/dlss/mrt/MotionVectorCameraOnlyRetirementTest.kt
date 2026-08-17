package me.snowmii.dlss.mrt

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.IndexType
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.shaders.ShaderSource
import com.mojang.blaze3d.systems.CommandEncoder
import com.mojang.blaze3d.systems.CommandEncoderBackend
import com.mojang.blaze3d.systems.DeviceFeatures
import com.mojang.blaze3d.systems.DeviceInfo
import com.mojang.blaze3d.systems.DeviceLimits
import com.mojang.blaze3d.systems.DeviceType
import com.mojang.blaze3d.systems.GpuDeviceBackend
import com.mojang.blaze3d.systems.GpuQueryPool
import com.mojang.blaze3d.systems.GpuSurfaceBackend
import com.mojang.blaze3d.systems.HintsAndWorkarounds
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderPassBackend
import com.mojang.blaze3d.systems.RenderPassDescriptor
import com.mojang.blaze3d.textures.AddressMode
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuSampler
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.nio.file.Files
import java.util.Optional
import java.util.OptionalDouble
import java.util.function.Supplier
import org.joml.Vector4fc
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.PointerBuffer

/**
 * Camera-only writer retirement: on VELOCITY_MRT the five
 * camera-motion-only writer families - terrain, static block entity, weather, particle, and
 * breaking block - are gone from the writer surface, the mixin registration, the source tree,
 * and the shader assets, while the retained object-motion writers (entity, moving block,
 * cloud), the post-scene fill, and the terrain pass's sentinel clear stay. The hand
 * retirement rides the same ratchet: the first-person hand/item velocity writer, its mixin
 * hooks, its shaders, and its suite are absent too, while the vanilla post-DLSS hand route
 * outside the world phase stays untouched.
 *
 * Three seams carry the proof:
 *
 * 1. **Terrain pass creation on a recording backend.** The terrain mixin delegates the
 *    `renderGroup` pass-creation redirect's clear to [TerrainVelocityPass], a plain object
 *    the test JVM can drive; the handler routes the pass itself through the wrapped
 *    operation with the original arguments, so the pass keeps the exact shape the caller
 *    asked for. Driving the helper proves the clear lifecycle: the opaque group's sentinel
 *    clear lands on the encoder before the pass exists, the translucent group (or a null
 *    velocity view) never clears, and the pass that follows carries exactly the one
 *    attachment the caller requested - the vanilla one source color attachment, no velocity
 *    attachment, no twin.
 *
 * 2. **Writer and registration surface.** [VelocityWriter] exposes only the retained
 *    object-motion families, and `mc-dlss.mixins.json` registers no retired motion mixin.
 *
 * 3. **Policy ratchet.** The retired camera-only mixins, writer classes, shaders, and their
 *    test suites are absent from the source tree while the retained writers and the fill
 *    remain present. This is the sanctioned source ratchet, not a change detector: a
 *    re-introduced camera-only writer would break nothing that a green suite drives (the
 *    writer mixins cannot run without a Fabric transformation), so only an absence check can
 *    catch that invisible bug.
 */
class MotionVectorCameraOnlyRetirementTest {

	@Test
	fun `terrain pass creation keeps the vanilla one-attachment shape and emits one pre-object-write sentinel clear`() {
		val backend = RecordingBackend()
		val encoder = RecordingEncoder(backend)
		val scene = FakeView(FakeTexture(GpuFormat.RGBA8_UNORM))
		val velocity = FakeView(ClearableFakeTexture(GpuFormat.RG16_FLOAT))
		val depth = FakeView(FakeTexture(GpuFormat.D32_FLOAT))

		// The opaque group: the companion clears to the sentinel before the pass exists, then
		// the pass is created through the callback - the caller's vanilla one source
		// attachment, no velocity attachment added by the helper.
		val pass = TerrainVelocityPass.createPass(
			encoder,
			velocity,
			clearBeforeObjectWrites = true,
		) {
			encoder.createRenderPass({ "terrain" }, scene, Optional.empty(), depth, OptionalDouble.empty())
		}
		pass.close()

		// The clear is one encoder command, recorded before the pass creation.
		assertEquals(listOf("clear", "pass"), backend.events, "the clear must land before the pass exists")
		assertEquals(listOf(velocity.texture() to TerrainVelocityPass.SENTINEL), backend.clears)
		assertSame(TerrainVelocityPass.SENTINEL, backend.clears.single().second)

		// The pass carries exactly the vanilla one source attachment: scene color, no velocity.
		val descriptor = backend.passDescriptors.single()
		assertEquals(1, descriptor.colorAttachments().size, "the terrain pass has exactly one color attachment")
		assertSame(scene, descriptor.colorAttachments()[0]!!.textureView())
	}

	@Test
	fun `the translucent group and a null velocity view never clear and keep the vanilla pass`() {
		val backend = RecordingBackend()
		val encoder = RecordingEncoder(backend)
		val scene = FakeView(FakeTexture(GpuFormat.RGBA8_UNORM))
		val velocity = FakeView(FakeTexture(GpuFormat.RG16_FLOAT))

		// The translucent group loads the companion: no clear, but the pass is still the exact
		// vanilla one-attachment shape.
		TerrainVelocityPass.createPass(
			encoder,
			velocity,
			clearBeforeObjectWrites = false,
		) {
			encoder.createRenderPass({ "terrain" }, scene, Optional.empty(), null, OptionalDouble.empty())
		}.close()
		assertEquals(listOf("pass"), backend.events, "the translucent group never clears")
		assertEquals(1, backend.passDescriptors.single().colorAttachments().size)

		// A null velocity view (closed phase, vanilla session, latched camera-only route):
		// exact vanilla pass, no clear, cannot throw.
		TerrainVelocityPass.createPass(
			encoder,
			null,
			clearBeforeObjectWrites = true,
		) {
			encoder.createRenderPass({ "terrain" }, scene, Optional.empty(), null, OptionalDouble.empty())
		}.close()
		assertEquals(listOf("pass", "pass"), backend.events)
		assertTrue(backend.clears.isEmpty())
		assertEquals(2, backend.passDescriptors.size)
		backend.passDescriptors.forEach { assertEquals(1, it.colorAttachments().size) }
	}

	@Test
	fun `velocity writer surface exposes only the retained object-motion families`() {
		assertEquals(
			listOf("entity", "movingblock", "cloud"),
			VelocityWriter.entries.map { it.segment },
			"only the retained object-motion writer families may exist",
		)
		assertEquals(
			setOf("velocity_entity", "velocity_block", "velocity_clouds"),
			VelocityWriter.entries.map { it.fragmentShader.path.removePrefix("core/") }.toSet(),
			"the retained writers reference only the retained shader assets",
		)
	}

	@Test
	fun `mixin registration keeps only the retained motion mixins`() {
		val registration = repositorySource("src/main/resources/mc-dlss.mixins.json")

		for (retired in listOf(
			"WeatherEffectRendererMotionMixin",
			"QuadParticleFeatureRendererMotionMixin",
			// The retired hand/item writer hooks and their submit-identity copies.
			"ItemInHandRendererMotionMixin",
			"ItemFeatureRendererMotionMixin",
			"ItemFeatureRendererSubmitMotionMixin",
			"TextFeatureRendererMotionMixin",
			"TextFeatureRendererSubmitMotionMixin",
			"CustomFeatureRendererMotionMixin",
			"CustomFeatureRendererSubmitMotionMixin",
		)) {
			assertFalse(registration.contains(retired), "$retired must be retired from the registration")
		}
		for (retained in listOf(
			// Retained object-motion writer routes and their seams.
			"EntityRenderDispatcherMotionMixin",
			"MovingBlockFeatureRendererMotionMixin",
			"CloudRendererMotionMixin",
			"PreparedRenderTypeMotionMixin",
			"RenderTypeFeatureRendererMotionMixin",
			"StagedVertexBufferMotionMixin",
			"ModelFeatureRendererMotionMixin",
			"ModelFeatureSubmitMotionMixin",
			"BlockEntityRenderDispatcherMotionMixin",
			// The terrain pass clear seam and the compatibility latch backstop.
			"VulkanChunkSectionsToRenderMixin",
			"VulkanPipelineCompatibilityMixin",
		)) {
			assertTrue(registration.contains(retained), "$retained must stay registered")
		}
	}

	@Test
	fun `retired camera-only writers shaders and tests are absent while the fill and retained writers remain`() {
		for (retired in RETIRED_SOURCES) {
			assertFalse(Files.exists(repositoryRoot.resolve(retired)), "$retired must be retired")
		}
		for (retained in RETAINED_SOURCES) {
			assertTrue(Files.exists(repositoryRoot.resolve(retained)), "$retained must remain")
		}

		// The post-scene fill stays the sole camera-motion reconstructor into the native
		// motion image: its ABI carrier and dispatch owner remain, and no retired writer class
		// (the only other camera-motion writers) remains to compete with it.
		assertTrue(Files.exists(repositoryRoot.resolve("src/main/kotlin/me/snowmii/dlss/render/FrameEvaluation.kt")))
	}

	/** A velocity texture that satisfies the clear path's usage checks, like the scene companion. */
	private class ClearableFakeTexture(format: GpuFormat, width: Int = 16, height: Int = 16) :
		GpuTexture(USAGE_RENDER_ATTACHMENT or USAGE_COPY_DST, "fake", format, width, height, 1, 1) {
		override fun close() = Unit
		override fun isClosed() = false
	}

	/** The recording encoder: records the pass descriptors and hands back a real pass. */
	private class RecordingEncoder(private val recording: RecordingBackend) :
		CommandEncoder(null, RecordingGpuDeviceBackend(), recording) {

		override fun createRenderPass(descriptor: RenderPassDescriptor): RenderPass {
			recording.passDescriptors += descriptor
			recording.events += "pass"
			return RenderPass(
				RecordingPassBackend(),
				RecordingGpuDeviceBackend(),
				descriptor.colorAttachments(),
				{},
				descriptor.renderArea,
			)
		}
	}

	/** The recording backend: captures the sentinel clear and the event order. */
	private class RecordingBackend : CommandEncoderBackend {
		val events = mutableListOf<String>()
		val clears = mutableListOf<Pair<GpuTexture, Vector4fc>>()
		val passDescriptors = mutableListOf<RenderPassDescriptor>()

		override fun clearColorTexture(colorTexture: GpuTexture, clearColor: Vector4fc) {
			clears += colorTexture to clearColor
			events += "clear"
		}

		override fun submit() = Unit
		override fun transientMemory() = throw UnsupportedOperationException("test backend never allocates transient memory")
		override fun createRenderPass(descriptor: RenderPassDescriptor): RenderPassBackend = throw UnsupportedOperationException("test backend never creates passes")
		override fun submitRenderPass() = Unit
		override fun clearColorAndDepthTextures(colorTexture: GpuTexture, clearColor: Vector4fc, depthTexture: GpuTexture, clearDepth: Double) = Unit
		override fun clearColorAndDepthTextures(colorTexture: GpuTexture, clearColor: Vector4fc, depthTexture: GpuTexture, clearDepth: Double, regionX: Int, regionY: Int, regionWidth: Int, regionHeight: Int) = Unit
		override fun clearDepthTexture(depthTexture: GpuTexture, clearDepth: Double) = Unit
		override fun writeToBuffer(destination: GpuBufferSlice, data: ByteBuffer) = Unit
		override fun copyToBuffer(source: GpuBufferSlice, target: GpuBufferSlice) = Unit
		override fun writeToTexture(destination: GpuTexture, source: ByteBuffer, mipLevel: Int, depthOrLayer: Int, destX: Int, destY: Int, width: Int, height: Int) = Unit
		override fun copyBufferToTexture(source: GpuBufferSlice, sourceX: Int, sourceY: Int, sourceWidth: Int, sourceHeight: Int, destination: GpuTexture, destinationX: Int, destinationY: Int, copyWidth: Int, copyHeight: Int, mipLevel: Int, arrayLayer: Int) = Unit
		override fun copyTextureToBuffer(source: GpuTexture, destination: GpuBuffer, offset: Long, callback: Runnable, mipLevel: Int) = Unit
		override fun copyTextureToBuffer(source: GpuTexture, destination: GpuBuffer, offset: Long, callback: Runnable, mipLevel: Int, x: Int, y: Int, width: Int, height: Int) = Unit
		override fun copyTextureToTexture(source: GpuTexture, destination: GpuTexture, mipLevel: Int, destX: Int, destY: Int, sourceX: Int, sourceY: Int, width: Int, height: Int) = Unit
		override fun createFence() = throw UnsupportedOperationException("test backend never creates fences")
		override fun writeTimestamp(pool: GpuQueryPool, index: Int) = Unit
	}

	private class RecordingPassBackend : RenderPassBackend {
		override fun pushDebugGroup(label: Supplier<String>) = Unit
		override fun popDebugGroup() = Unit
		override fun setPipeline(pipeline: RenderPipeline) = Unit
		override fun bindTexture(name: String, textureView: GpuTextureView?, sampler: GpuSampler?) = Unit
		override fun setUniform(name: String, value: GpuBuffer) = Unit
		override fun setUniform(name: String, value: GpuBufferSlice) = Unit
		override fun enableScissor(x: Int, y: Int, width: Int, height: Int) = Unit
		override fun disableScissor() = Unit
		override fun setVertexBuffer(slot: Int, vertexBuffer: GpuBufferSlice?) = Unit
		override fun setIndexBuffer(indexBuffer: GpuBuffer, indexType: IndexType) = Unit
		override fun drawIndexed(indexCount: Int, instanceCount: Int, firstIndex: Int, vertexOffset: Int, firstInstance: Int) = Unit
		override fun multiDrawIndexed(drawParameters: IntBuffer, instanceCount: Int, firstInstance: Int, drawCount: Int) = Unit
		override fun multiDrawIndexed(firstIndexOffsets: PointerBuffer, indexCounts: IntBuffer, vertexOffsets: IntBuffer, drawCount: Int) = Unit
		override fun drawIndexedIndirect(commands: GpuBufferSlice, drawCount: Int) = Unit
		override fun <T : Any> drawMultipleIndexed(
			draws: Collection<RenderPass.Draw<T>>,
			defaultIndexBuffer: GpuBuffer?,
			defaultIndexType: IndexType?,
			dynamicUniforms: Collection<String>,
			uniformArgument: T,
		) = Unit
		override fun draw(vertexCount: Int, instanceCount: Int, firstVertex: Int, firstInstance: Int) = Unit
		override fun multiDraw(drawParameters: IntBuffer, instanceCount: Int, firstInstance: Int, drawCount: Int) = Unit
		override fun multiDraw(firstVertices: IntBuffer, vertexCounts: IntBuffer, drawCount: Int) = Unit
		override fun drawIndirect(commands: GpuBufferSlice, drawCount: Int) = Unit
		override fun writeTimestamp(pool: GpuQueryPool, index: Int) = Unit
	}

	private class RecordingGpuDeviceBackend : GpuDeviceBackend {
		private val info = DeviceInfo(
			"fake",
			"fake",
			"fake",
			true,
			"fake",
			1f,
			DeviceLimits(16, 4, 65536, 1L shl 30, 1, 8),
			DeviceFeatures(true, false, false, false, false, true, false),
			emptySet(),
			HintsAndWorkarounds(false, false),
			DeviceType.OTHER,
		)

		override fun createSurface(windowHandle: Long): GpuSurfaceBackend = throw UnsupportedOperationException("test backend never creates surfaces")
		override fun createCommandEncoder(): CommandEncoderBackend = throw UnsupportedOperationException("test backend never creates encoders")
		override fun createSampler(addressModeU: AddressMode, addressModeV: AddressMode, minFilter: FilterMode, magFilter: FilterMode, maxAnisotropy: Int, maxLod: OptionalDouble): GpuSampler =
			throw UnsupportedOperationException("test backend never creates samplers")
		override fun createTexture(label: Supplier<String>?, usage: Int, format: GpuFormat, width: Int, height: Int, depthOrLayers: Int, mipLevels: Int): GpuTexture =
			throw UnsupportedOperationException("test backend never creates textures")
		override fun createTexture(label: String?, usage: Int, format: GpuFormat, width: Int, height: Int, depthOrLayers: Int, mipLevels: Int): GpuTexture =
			throw UnsupportedOperationException("test backend never creates textures")
		override fun createTextureView(texture: GpuTexture): GpuTextureView = throw UnsupportedOperationException("test backend never creates texture views")
		override fun createTextureView(texture: GpuTexture, baseMipLevel: Int, mipLevels: Int): GpuTextureView =
			throw UnsupportedOperationException("test backend never creates texture views")
		override fun createBuffer(label: Supplier<String>?, usage: Int, size: Long): GpuBuffer = throw UnsupportedOperationException("test backend never allocates buffers")
		override fun createBuffer(label: Supplier<String>?, usage: Int, data: ByteBuffer): GpuBuffer = throw UnsupportedOperationException("test backend never allocates buffers")
		override fun getLastDebugMessages(): List<String> = emptyList()
		override fun isDebuggingEnabled(): Boolean = false
		override fun precompilePipeline(pipeline: RenderPipeline, shaderSource: ShaderSource?): CompiledRenderPipeline =
			throw UnsupportedOperationException("test backend never compiles pipelines")
		override fun clearPipelineCache() = Unit
		override fun close() = Unit
		override fun createTimestampQueryPool(size: Int): GpuQueryPool = throw UnsupportedOperationException("test backend never creates query pools")
		override fun getTimestampNow(): Long = 0L
		override fun getDeviceInfo(): DeviceInfo = info
	}

	private companion object {
		val RETIRED_SOURCES = listOf(
			"src/main/java/me/snowmii/dlss/mixin/WeatherEffectRendererMotionMixin.java",
			"src/main/java/me/snowmii/dlss/mixin/QuadParticleFeatureRendererMotionMixin.java",
			"src/main/kotlin/me/snowmii/dlss/mrt/WeatherVelocityRender.kt",
			"src/main/kotlin/me/snowmii/dlss/mrt/ParticleVelocityRender.kt",
			"src/main/kotlin/me/snowmii/dlss/mrt/BreakingBlockVelocityRender.kt",
			"src/main/kotlin/me/snowmii/dlss/mrt/TerrainVelocityUniforms.kt",
			"src/main/resources/assets/mc-dlss/shaders/core/velocity_terrain.fsh",
			"src/main/resources/assets/mc-dlss/shaders/core/velocity_weather.fsh",
			"src/main/resources/assets/mc-dlss/shaders/core/velocity_crumbling.fsh",
			"src/test/kotlin/me/snowmii/dlss/mrt/MotionVectorBlockEntityTest.kt",
			"src/test/kotlin/me/snowmii/dlss/mrt/MotionVectorBreakingBlockTest.kt",
		)

		val RETAINED_SOURCES = listOf(
			"src/main/kotlin/me/snowmii/dlss/mrt/EntityVelocityUniforms.kt",
			"src/main/kotlin/me/snowmii/dlss/mrt/MovingBlockVelocityRender.kt",
			"src/main/kotlin/me/snowmii/dlss/mrt/CloudVelocityRender.kt",
			"src/main/kotlin/me/snowmii/dlss/mrt/TerrainVelocityPass.kt",
			"src/main/resources/assets/mc-dlss/shaders/core/velocity_entity.fsh",
			"src/main/resources/assets/mc-dlss/shaders/core/velocity_block.fsh",
			"src/main/resources/assets/mc-dlss/shaders/core/velocity_clouds.fsh",
			"streamline/src/main/java/me/snowmii/streamline/FillVelocityRequest.java",
		)
	}
}
