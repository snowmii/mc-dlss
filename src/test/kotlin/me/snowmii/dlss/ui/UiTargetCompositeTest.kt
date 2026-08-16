package me.snowmii.dlss.ui

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.IndexType
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.buffers.GpuFence
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.pipeline.RenderTarget
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
import com.mojang.blaze3d.systems.TransientMemory
import com.mojang.blaze3d.textures.AddressMode
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuSampler
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.util.Optional
import java.util.OptionalDouble
import java.util.function.Supplier
import org.joml.Vector4fc
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.PointerBuffer

/**
 * The UI split substrate: a transparent full-resolution RGBA8 UI target with depth that
 * allocates at output size and releases on close, and an injectable premultiplied
 * UI-over-hudless composite that writes its destination.
 *
 * Everything is driven off the render thread: the target lifecycle through injected
 * allocate/release pairs, the transparent clear and both composite passes through a recording
 * command encoder whose passes run their real bodies against recording backends, and the
 * premultiplied blend at the pipeline-descriptor seam.
 */
class UiTargetCompositeTest {
	private val outputWidth = 2560
	private val outputHeight = 1440

	@Test
	fun `UI target allocates at output size and reuses the target while the size holds`() {
		val allocated = mutableListOf<FakeTarget>()
		val released = mutableListOf<FakeTarget>()
		val uiTarget = UiTarget(
			allocate = { width, height -> FakeTarget(width, height).also(allocated::add) },
			release = { released += it as FakeTarget },
		)

		val first = uiTarget.acquire(outputWidth, outputHeight)
		val second = uiTarget.acquire(outputWidth, outputHeight)

		assertSame(first, second, "the target is reused while the output size holds")
		assertEquals(1, allocated.size)
		assertTrue(released.isEmpty())
		assertEquals(outputWidth, first.width)
		assertEquals(outputHeight, first.height)
	}

	@Test
	fun `resize releases the old target before allocating at the new size`() {
		val allocated = mutableListOf<FakeTarget>()
		val released = mutableListOf<FakeTarget>()
		val uiTarget = UiTarget(
			allocate = { width, height -> FakeTarget(width, height).also(allocated::add) },
			release = { released += it as FakeTarget },
		)
		val first = uiTarget.acquire(outputWidth, outputHeight)

		val second = uiTarget.acquire(1920, 1080)

		assertNotSame(first, second)
		assertEquals(listOf(first), released, "the old target is released before the new allocation")
		assertEquals(2, allocated.size)
		assertEquals(1920, second.width)
		assertEquals(1080, second.height)
	}

	@Test
	fun `close releases the held UI target exactly once`() {
		val released = mutableListOf<FakeTarget>()
		val uiTarget = UiTarget(
			allocate = { width, height -> FakeTarget(width, height) },
			release = { released += it as FakeTarget },
		)
		val held = uiTarget.acquire(outputWidth, outputHeight)

		uiTarget.close()
		uiTarget.close()

		assertEquals(listOf(held), released)
		assertNull(uiTarget.current)
	}

	@Test
	fun `the UI target format is the transparent full-resolution RGBA8`() {
		assertEquals(GpuFormat.RGBA8_UNORM, UiTarget.FORMAT)
	}

	@Test
	fun `clear resets the held target to transparent black and the reversed-Z far depth`() {
		val held = FakeTarget(outputWidth, outputHeight)
		val uiTarget = UiTarget(allocate = { _, _ -> held }, release = {})
		uiTarget.acquire(outputWidth, outputHeight)

		val recording = Recording()
		uiTarget.clear(recording.encoder())

		val clear = recording.clears.single()
		assertSame(held.colorTexture, clear.color, "the held target's color is cleared")
		assertSame(held.depthTexture, clear.depth, "the held target's depth is cleared")
		assertEquals(0f, clear.colorValue.x())
		assertEquals(0f, clear.colorValue.y())
		assertEquals(0f, clear.colorValue.z())
		assertEquals(0f, clear.colorValue.w(), "transparent black, so nothing drawn stays invisible")
		assertEquals(0.0, clear.depthValue, "reversed-Z: the far plane is the cleared depth")
	}

	@Test
	fun `composite writes the hudless world into the destination then blends the UI over it`() {
		val ui = FakeTarget(outputWidth, outputHeight)
		val hudless = FakeTarget(outputWidth, outputHeight)
		val destination = FakeTarget(outputWidth, outputHeight)
		val recording = Recording()

		UiComposite(sampler = { FakeSampler() }).render(recording.encoder(), ui, hudless, destination)

		assertEquals(2, recording.pipelines.size)
		assertSame(UiComposite.HUDLESS_COPY_PIPELINE, recording.pipelines[0], "the base is the unblended world copy")
		assertSame(UiComposite.UI_OVERLAY_PIPELINE, recording.pipelines[1], "the overlay is the premultiplied UI blend")
		assertEquals(listOf("InSampler", "InSampler"), recording.binds.map { it.first })
		assertSame(hudless.colorTextureView, recording.binds[0].second, "the first pass samples the HUD-less world")
		assertSame(ui.colorTextureView, recording.binds[1].second, "the second pass samples the UI target")
		assertEquals(listOf(3 to 1, 3 to 1), recording.draws, "both passes are full-screen draws")
		assertEquals(2, recording.passCloses)
		for (descriptor in recording.descriptors) {
			assertSame(
				destination.colorTextureView,
				descriptor.colorAttachments().single()!!.textureView(),
				"every pass writes the destination",
			)
		}
	}

	@Test
	fun `the overlay pipeline carries the premultiplied RGBA8 full-write blend and the copy is unblended`() {
		val overlay = checkNotNull(UiComposite.UI_OVERLAY_PIPELINE.colorTargetState)
		assertEquals(Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA), overlay.blendFunction())
		assertEquals(GpuFormat.RGBA8_UNORM, overlay.format())
		assertEquals(ColorTargetState.WRITE_ALL, overlay.writeMask())

		val copy = checkNotNull(UiComposite.HUDLESS_COPY_PIPELINE.colorTargetState)
		assertTrue(copy.blendFunction().isEmpty(), "the base copy replaces the destination, never blends")
		assertEquals(GpuFormat.RGBA8_UNORM, copy.format())
		assertEquals(ColorTargetState.WRITE_ALL, copy.writeMask())
	}

	@Test
	fun `a missing color view writes no partial composite`() {
		val recording = Recording()
		val hudless = FakeTarget(outputWidth, outputHeight)
		val destination = FakeTarget(outputWidth, outputHeight)

		UiComposite(sampler = { FakeSampler() }).render(recording.encoder(), FakeTarget(outputWidth, outputHeight, withViews = false), hudless, destination)

		assertTrue(recording.pipelines.isEmpty(), "no pass is recorded when a source has no color view")
	}

	/** Render target with fake GPU textures and views, so lifetime and passes are testable off the render thread. */
	private class FakeTarget(
		width: Int,
		height: Int,
		withViews: Boolean = true,
	) : RenderTarget("fake-ui", true, GpuFormat.RGBA8_UNORM) {
		init {
			this.width = width
			this.height = height
			if (withViews) {
				colorTexture = FakeTexture(GpuFormat.RGBA8_UNORM, width, height)
				depthTexture = FakeTexture(GpuFormat.D32_FLOAT, width, height)
				colorTextureView = FakeView(colorTexture!!)
				depthTextureView = FakeView(depthTexture!!)
			}
		}

		override fun createBuffers(width: Int, height: Int) {
			this.width = width
			this.height = height
		}

		override fun destroyBuffers() = Unit
	}

	private class FakeTexture(format: GpuFormat, width: Int, height: Int) :
		GpuTexture(GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_COPY_DST, "fake", format, width, height, 1, 1) {
		override fun close() = Unit
		override fun isClosed() = false
	}

	private class FakeView(texture: GpuTexture) : GpuTextureView(texture, 0, 1) {
		override fun close() = Unit
		override fun isClosed() = false
	}

	private class FakeSampler : GpuSampler() {
		override fun getAddressModeU() = AddressMode.CLAMP_TO_EDGE
		override fun getAddressModeV() = AddressMode.CLAMP_TO_EDGE
		override fun getMinFilter() = FilterMode.NEAREST
		override fun getMagFilter() = FilterMode.NEAREST
		override fun getMaxAnisotropy() = 1
		override fun getMaxLod(): OptionalDouble = OptionalDouble.of(0.0)
		override fun close() = Unit
	}

	/** Records every clear, pass, pipeline, bind, and draw the production code drives. */
	private class Recording {
		data class Clear(val color: GpuTexture, val colorValue: Vector4fc, val depth: GpuTexture, val depthValue: Double)

		val clears = mutableListOf<Clear>()
		val descriptors = mutableListOf<RenderPassDescriptor>()
		val pipelines = mutableListOf<RenderPipeline>()
		val binds = mutableListOf<Pair<String, GpuTextureView?>>()
		val draws = mutableListOf<Pair<Int, Int>>()
		var passCloses = 0

		fun encoder() = RecordingEncoder(this, FakeGpuDeviceBackend(), RecordingCommandBackend(this))
	}

	/** The recording encoder: builds real [RenderPass]es over the recording backends so the pass bodies run. */
	private class RecordingEncoder(
		private val recording: Recording,
		private val deviceBackend: GpuDeviceBackend,
		commandBackend: CommandEncoderBackend,
	) : CommandEncoder(null, deviceBackend, commandBackend) {
		override fun createRenderPass(descriptor: RenderPassDescriptor): RenderPass {
			recording.descriptors += descriptor
			return RenderPass(
				RecordingPassBackend(recording),
				deviceBackend,
				descriptor.colorAttachments(),
				{ recording.passCloses++ },
				descriptor.renderArea,
			)
		}
	}

	/** Records the pass-body calls the composite makes. */
	private class RecordingPassBackend(private val recording: Recording) : RenderPassBackend {
		override fun pushDebugGroup(label: Supplier<String>) = Unit
		override fun popDebugGroup() = Unit
		override fun setPipeline(pipeline: RenderPipeline) {
			recording.pipelines += pipeline
		}

		override fun bindTexture(name: String, textureView: GpuTextureView?, sampler: GpuSampler?) {
			recording.binds += name to textureView
		}

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

		override fun draw(vertexCount: Int, instanceCount: Int, firstVertex: Int, firstInstance: Int) {
			recording.draws += vertexCount to instanceCount
		}

		override fun multiDraw(drawParameters: IntBuffer, instanceCount: Int, firstInstance: Int, drawCount: Int) = Unit
		override fun multiDraw(firstVertices: IntBuffer, vertexCounts: IntBuffer, drawCount: Int) = Unit
		override fun drawIndirect(commands: GpuBufferSlice, drawCount: Int) = Unit
		override fun writeTimestamp(pool: GpuQueryPool, index: Int) = Unit
	}

	/** The command-encoder backend: records the transparent clear; every other call is unreachable. */
	private class RecordingCommandBackend(private val recording: Recording) : CommandEncoderBackend {
		override fun submit() = Unit
		override fun transientMemory(): TransientMemory = throw UnsupportedOperationException("UI tests never allocate transient memory")
		override fun createRenderPass(descriptor: RenderPassDescriptor): com.mojang.blaze3d.systems.RenderPassBackend =
			throw UnsupportedOperationException("UI tests never create passes through the backend")
		override fun submitRenderPass() = Unit
		override fun clearColorTexture(colorTexture: GpuTexture, clearColor: Vector4fc) = Unit
		override fun clearColorAndDepthTextures(colorTexture: GpuTexture, clearColor: Vector4fc, depthTexture: GpuTexture, clearDepth: Double) {
			recording.clears += Recording.Clear(colorTexture, clearColor, depthTexture, clearDepth)
		}

		override fun clearColorAndDepthTextures(
			colorTexture: GpuTexture,
			clearColor: Vector4fc,
			depthTexture: GpuTexture,
			clearDepth: Double,
			regionX: Int,
			regionY: Int,
			regionWidth: Int,
			regionHeight: Int,
		) = Unit

		override fun clearDepthTexture(depthTexture: GpuTexture, clearDepth: Double) = Unit
		override fun writeToBuffer(destination: GpuBufferSlice, data: ByteBuffer) = Unit
		override fun copyToBuffer(source: GpuBufferSlice, target: GpuBufferSlice) = Unit
		override fun writeToTexture(destination: GpuTexture, source: ByteBuffer, mipLevel: Int, depthOrLayer: Int, destX: Int, destY: Int, width: Int, height: Int) = Unit
		override fun copyBufferToTexture(source: GpuBufferSlice, sourceX: Int, sourceY: Int, sourceWidth: Int, sourceHeight: Int, destination: GpuTexture, destinationX: Int, destinationY: Int, copyWidth: Int, copyHeight: Int, mipLevel: Int, arrayLayer: Int) = Unit
		override fun copyTextureToBuffer(source: GpuTexture, destination: GpuBuffer, offset: Long, callback: Runnable, mipLevel: Int) = Unit
		override fun copyTextureToBuffer(source: GpuTexture, destination: GpuBuffer, offset: Long, callback: Runnable, mipLevel: Int, x: Int, y: Int, width: Int, height: Int) = Unit
		override fun copyTextureToTexture(source: GpuTexture, destination: GpuTexture, mipLevel: Int, destX: Int, destY: Int, sourceX: Int, sourceY: Int, width: Int, height: Int) = Unit
		override fun createFence(): GpuFence = throw UnsupportedOperationException("UI tests never create fences")
		override fun writeTimestamp(pool: GpuQueryPool, index: Int) = Unit
	}

	/** The device-info side of the fake device the RenderPass constructor requires; never driven. */
	private class FakeGpuDeviceBackend : GpuDeviceBackend {
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

		override fun createSurface(windowHandle: Long): GpuSurfaceBackend = throw UnsupportedOperationException("UI tests never create surfaces")
		override fun createCommandEncoder(): com.mojang.blaze3d.systems.CommandEncoderBackend =
			throw UnsupportedOperationException("UI tests never create encoders")
		override fun createSampler(addressModeU: AddressMode, addressModeV: AddressMode, minFilter: FilterMode, magFilter: FilterMode, maxAnisotropy: Int, maxLod: OptionalDouble): GpuSampler =
			throw UnsupportedOperationException("UI tests never create samplers")
		override fun createTexture(label: Supplier<String>?, usage: Int, format: GpuFormat, width: Int, height: Int, depthOrLayers: Int, mipLevels: Int): GpuTexture =
			throw UnsupportedOperationException("UI tests never create textures")
		override fun createTexture(label: String?, usage: Int, format: GpuFormat, width: Int, height: Int, depthOrLayers: Int, mipLevels: Int): GpuTexture =
			throw UnsupportedOperationException("UI tests never create textures")
		override fun createTextureView(texture: GpuTexture): GpuTextureView = throw UnsupportedOperationException("UI tests never create texture views")
		override fun createTextureView(texture: GpuTexture, baseMipLevel: Int, mipLevels: Int): GpuTextureView =
			throw UnsupportedOperationException("UI tests never create texture views")
		override fun createBuffer(label: Supplier<String>?, usage: Int, size: Long): GpuBuffer =
			throw UnsupportedOperationException("UI tests never allocate buffers")
		override fun createBuffer(label: Supplier<String>?, usage: Int, data: ByteBuffer): GpuBuffer =
			throw UnsupportedOperationException("UI tests never allocate buffers")
		override fun getLastDebugMessages(): List<String> = emptyList()
		override fun isDebuggingEnabled(): Boolean = false
		override fun precompilePipeline(pipeline: RenderPipeline, shaderSource: ShaderSource?): CompiledRenderPipeline =
			throw UnsupportedOperationException("UI tests never compile pipelines")
		override fun clearPipelineCache() = Unit
		override fun close() = Unit
		override fun createTimestampQueryPool(size: Int): GpuQueryPool = throw UnsupportedOperationException("UI tests never create query pools")
		override fun getTimestampNow(): Long = 0L
		override fun getDeviceInfo(): DeviceInfo = info
	}
}
