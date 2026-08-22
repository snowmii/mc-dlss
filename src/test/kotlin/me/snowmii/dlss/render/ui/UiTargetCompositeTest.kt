package me.snowmii.dlss.render.ui

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.pipeline.IndexType
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.commands.GpuFence
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.renderpearl.api.commands.CommandEncoder
import com.mojang.renderpearl.api.commands.GpuQueryPool
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.commands.RenderPassDescriptor
import com.mojang.renderpearl.api.buffers.TransientMemory
import com.mojang.renderpearl.api.textures.AddressMode
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import me.snowmii.dlss.render.ui.UiComposite
import me.snowmii.dlss.render.ui.UiTarget
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
 * Transparent full-resolution RGBA8 UI target with depth, and a premultiplied UI-over-hudless
 * composite that writes its destination. Lifetime and both passes are driven off the render
 * thread.
 */
class UiTargetCompositeTest {
	private val outputWidth = 2560
	private val outputHeight = 1440

	@Test
	fun `UI target allocates at output size and reuses the target while the size holds`() {
		val allocated = mutableListOf<HeadlessRenderTarget>()
		val released = mutableListOf<HeadlessRenderTarget>()
		val uiTarget = UiTarget(
			allocate = { width, height -> HeadlessRenderTarget(width, height).also(allocated::add) },
			release = { released += it as HeadlessRenderTarget },
		)

		val first = uiTarget.acquireUiTarget(outputWidth, outputHeight)
		val second = uiTarget.acquireUiTarget(outputWidth, outputHeight)

		assertSame(first, second, "the target is reused while the output size holds")
		assertEquals(1, allocated.size)
		assertTrue(released.isEmpty())
		assertEquals(outputWidth, first.width)
		assertEquals(outputHeight, first.height)
	}

	@Test
	fun `resize releases the old target before allocating at the new size`() {
		val allocated = mutableListOf<HeadlessRenderTarget>()
		val released = mutableListOf<HeadlessRenderTarget>()
		val uiTarget = UiTarget(
			allocate = { width, height -> HeadlessRenderTarget(width, height).also(allocated::add) },
			release = { released += it as HeadlessRenderTarget },
		)
		val first = uiTarget.acquireUiTarget(outputWidth, outputHeight)

		val second = uiTarget.acquireUiTarget(1920, 1080)

		assertNotSame(first, second)
		assertEquals(listOf(first), released, "the old target is released before the new allocation")
		assertEquals(2, allocated.size)
		assertEquals(1920, second.width)
		assertEquals(1080, second.height)
	}

	@Test
	fun `close releases the held UI target exactly once`() {
		val released = mutableListOf<HeadlessRenderTarget>()
		val uiTarget = UiTarget(
			allocate = { width, height -> HeadlessRenderTarget(width, height) },
			release = { released += it as HeadlessRenderTarget },
		)
		val held = uiTarget.acquireUiTarget(outputWidth, outputHeight)

		uiTarget.close()
		uiTarget.close()

		assertEquals(listOf(held), released)
		assertNull(uiTarget.currentUiTarget)
	}

	@Test
	fun `the UI target format is the transparent full-resolution RGBA8`() {
		assertEquals(GpuFormat.RGBA8_UNORM, UiTarget.FORMAT)
	}

	@Test
	fun `clear resets the held target to transparent black and the reversed-Z far depth`() {
		val held = HeadlessRenderTarget(outputWidth, outputHeight)
		val uiTarget = UiTarget(allocate = { _, _ -> held }, release = {})
		uiTarget.acquireUiTarget(outputWidth, outputHeight)

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
		val ui = HeadlessRenderTarget(outputWidth, outputHeight)
		val hudless = HeadlessRenderTarget(outputWidth, outputHeight)
		val destination = HeadlessRenderTarget(outputWidth, outputHeight)
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
		val overlay = UiComposite.UI_OVERLAY_PIPELINE.colorTargetStates.single()!!
		assertEquals(Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA), overlay.blendFunction())
		assertEquals(GpuFormat.RGBA8_UNORM, overlay.format())
		assertEquals(ColorTargetState.WRITE_ALL, overlay.writeMask())

		val copy = UiComposite.HUDLESS_COPY_PIPELINE.colorTargetStates.single()!!
		assertTrue(copy.blendFunction().isEmpty, "the base copy replaces the destination, never blends")
		assertEquals(GpuFormat.RGBA8_UNORM, copy.format())
		assertEquals(ColorTargetState.WRITE_ALL, copy.writeMask())
	}

	@Test
	fun `a missing color view writes no partial composite`() {
		val recording = Recording()
		val hudless = HeadlessRenderTarget(outputWidth, outputHeight)
		val destination = HeadlessRenderTarget(outputWidth, outputHeight)

		UiComposite(sampler = { FakeSampler() }).render(recording.encoder(), HeadlessRenderTarget(outputWidth, outputHeight, withViews = false), hudless, destination)

		assertTrue(recording.pipelines.isEmpty(), "no pass is recorded when a source has no color view")
	}

	/** Render target with fake GPU textures and views, so lifetime and passes are testable off the render thread. */
	private class HeadlessRenderTarget(
		width: Int,
		height: Int,
		withViews: Boolean = true,
	) : RenderTarget("fake-ui", GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT) {
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

	/** 26.3: [GpuTexture]/[GpuTextureView]/[GpuSampler] are plain interfaces; fakes implement them directly. */
	private class FakeTexture(private val format: GpuFormat, private val fakeWidth: Int, private val fakeHeight: Int) : GpuTexture {
		override fun getWidth(mipLevel: Int) = fakeWidth
		override fun getHeight(mipLevel: Int) = fakeHeight
		override fun getDepthOrLayers() = 1
		override fun getMipLevels() = 1
		override fun getFormat(): GpuFormat = format
		override fun usage() = GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_COPY_DST
		override fun getLabel() = "fake"
		override fun isClosed() = false
		override fun close() = Unit
	}

	private class FakeView(private val fakeTexture: GpuTexture) : GpuTextureView {
		override fun isClosed() = false
		override fun texture(): GpuTexture = fakeTexture
		override fun baseMipLevel() = 0
		override fun mipLevels() = 1
		override fun getWidth(mipLevel: Int) = fakeTexture.getWidth(mipLevel)
		override fun getHeight(mipLevel: Int) = fakeTexture.getHeight(mipLevel)
		override fun close() = Unit
	}

	private class FakeSampler : GpuSampler {
		override fun getAddressModeU() = AddressMode.CLAMP_TO_EDGE
		override fun getAddressModeV() = AddressMode.CLAMP_TO_EDGE
		override fun getMinFilter() = FilterMode.NEAREST
		override fun getMagFilter() = FilterMode.NEAREST
		override fun getMaxAnisotropy() = 1
		override fun getMaxLod(): OptionalDouble = OptionalDouble.of(0.0)
		override fun isClosed() = false
		override fun close() = Unit
	}

	private class Recording {
		data class Clear(val color: GpuTexture, val colorValue: Vector4fc, val depth: GpuTexture, val depthValue: Double)

		val clears = mutableListOf<Clear>()
		val descriptors = mutableListOf<RenderPassDescriptor>()
		val pipelines = mutableListOf<CompiledRenderPipeline>()
		val binds = mutableListOf<Pair<String, GpuTextureView?>>()
		val draws = mutableListOf<Pair<Int, Int>>()
		var passCloses = 0

		fun encoder() = RecordingEncoder(this)
	}

	/**
	 * 26.3: [CommandEncoder] and [RenderPass] are plain interfaces (no concrete class left to
	 * wrap with a backend), so the recording encoder and pass implement those interfaces
	 * directly instead of driving a real `RenderPass` through a backend split.
	 */
	private class RecordingEncoder(private val recording: Recording) : CommandEncoder {
		override fun submit() = Unit

		override fun transientMemory(): TransientMemory = throw UnsupportedOperationException("UI tests never allocate transient memory")

		override fun createRenderPass(descriptor: RenderPassDescriptor): RenderPass {
			recording.descriptors += descriptor
			return RecordingPass(recording)
		}

		override fun clearColorTexture(colorTexture: GpuTexture, clearColor: Vector4fc) = Unit

		override fun clearColorAndDepthTextures(colorTexture: GpuTexture, clearColor: Vector4fc, depthTexture: GpuTexture, clearDepth: Double) {
			recording.clears += Recording.Clear(colorTexture, clearColor, depthTexture, clearDepth)
		}

		override fun clearColorAndDepthTextures(
			colorTexture: GpuTexture,
			clearColor: Vector4fc,
			depthTexture: GpuTexture,
			clearDepth: Double,
			mipLevel: Int,
			regionX: Int,
			regionY: Int,
			regionWidth: Int,
			regionHeight: Int,
		) = Unit

		override fun clearDepthTexture(depthTexture: GpuTexture, clearDepth: Double) = Unit

		override fun writeToBuffer(destination: GpuBufferSlice, data: ByteBuffer) = Unit

		override fun copyToBuffer(source: GpuBufferSlice, target: GpuBufferSlice) = Unit

		override fun writeToTexture(destination: GpuTexture, source: com.mojang.blaze3d.platform.NativeImage) = Unit

		override fun writeToTexture(destination: GpuTexture, source: com.mojang.blaze3d.platform.NativeImage, x: Int, y: Int, width: Int, height: Int) = Unit

		override fun writeToTexture(destination: GpuTexture, source: ByteBuffer, mipLevel: Int, depthOrLayer: Int, destX: Int, destY: Int, width: Int, height: Int) = Unit

		override fun copyBufferToTexture(source: GpuBufferSlice, sourceX: Int, sourceY: Int, sourceWidth: Int, sourceHeight: Int, destination: GpuTexture, destinationX: Int, destinationY: Int, copyWidth: Int, copyHeight: Int, mipLevel: Int, arrayLayer: Int) = Unit

		override fun copyTextureToBuffer(source: GpuTexture, destination: GpuBuffer, offset: Long, callback: Runnable, mipLevel: Int) = Unit

		override fun copyTextureToBuffer(source: GpuTexture, destination: GpuBuffer, offset: Long, callback: Runnable, mipLevel: Int, x: Int, y: Int, width: Int, height: Int) = Unit

		override fun copyTextureToTexture(source: GpuTexture, destination: GpuTexture, mipLevel: Int, destX: Int, destY: Int, sourceX: Int, sourceY: Int, width: Int, height: Int) = Unit

		override fun createFence(): GpuFence = throw UnsupportedOperationException("UI tests never create fences")

		override fun writeTimestamp(pool: GpuQueryPool, index: Int) = Unit
	}

	/** Records the pass-body calls the UI composite makes: the bound pipeline, uniforms, and draws. */
	private class RecordingPass(private val recording: Recording) : RenderPass {
		override fun close() {
			recording.passCloses++
		}

		override fun pushDebugGroup(label: Supplier<String>) = Unit

		override fun popDebugGroup() = Unit

		override fun writeTimestamp(pool: GpuQueryPool, index: Int) = Unit

		override fun setPipeline(pipeline: CompiledRenderPipeline) {
			recording.pipelines += pipeline
		}

		override fun setUniform(name: String, textureView: GpuTextureView?, sampler: GpuSampler?) {
			recording.binds += name to textureView
		}

		override fun setUniform(name: String, value: GpuBuffer) = Unit

		override fun setUniform(name: String, value: GpuBufferSlice) = Unit

		override fun pushConstants(data: ByteBuffer) = Unit

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
	}
}
