package me.snowmii.dlss.render.ui

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.IndexType
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.buffers.GpuFence
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
import me.snowmii.dlss.render.ui.UiComposite
import me.snowmii.dlss.render.ui.UiPhase
import me.snowmii.dlss.render.ui.UiTarget
import java.nio.ByteBuffer
import java.nio.IntBuffer
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
 * At GUI completion the window closes and one composite bakes the held UI over the HUD-less
 * world already in the main target. The phase passes the main target as both HUD-less source
 * and destination; the composite skips the base copy — it must never sample the target it
 * renders into — so the vanilla main target is the presentation source before present,
 * screenshots, and Tracy.
 */
class UiCompositeFrameWiringTest {
	private val outputWidth = 2560
	private val outputHeight = 1440

	@Test
	fun `GUI completion closes the window and overlays the UI over the world already in the main target`() {
		val harness = Harness()
		val main = HeadlessRenderTarget(outputWidth, outputHeight)
		harness.phase.begin(main)

		harness.phase.endFrame()

		assertFalse(harness.phase.isOpen, "the GUI window closes at render completion")
		val ui = harness.allocated.single()
		assertEquals(
			listOf(UiComposite.UI_OVERLAY_PIPELINE),
			harness.recording.pipelines,
			"the aliased wiring skips the redundant base copy and runs only the UI overlay",
		)
		assertSame(ui.colorTextureView, harness.recording.binds.single().second, "the overlay pass samples the held UI target")
		assertTrue(
			harness.recording.binds.none { it.second === main.colorTextureView },
			"no pass samples the destination while it is attached as the render target",
		)
		for (descriptor in harness.recording.descriptors) {
			assertSame(
				main.colorTextureView,
				descriptor.colorAttachments().single()!!.textureView(),
				"the overlay writes the full-resolution main target",
			)
		}
		assertTrue(harness.released.isEmpty(), "the composite keeps the held UI target")
	}

	@Test
	fun `distinct HUD-less and destination targets keep both composite passes`() {
		val harness = Harness()
		val ui = HeadlessRenderTarget(outputWidth, outputHeight)
		val hudless = HeadlessRenderTarget(outputWidth, outputHeight)
		val destination = HeadlessRenderTarget(outputWidth, outputHeight)

		UiComposite(sampler = { FakeSampler() }).render(harness.recording.encoder(), ui, hudless, destination)

		assertEquals(
			listOf(UiComposite.HUDLESS_COPY_PIPELINE, UiComposite.UI_OVERLAY_PIPELINE),
			harness.recording.pipelines,
			"distinct inputs compose through the unblended HUD-less copy, then the premultiplied UI overlay",
		)
		assertSame(hudless.colorTextureView, harness.recording.binds[0].second, "the base pass samples the distinct HUD-less target")
		assertSame(ui.colorTextureView, harness.recording.binds[1].second, "the overlay pass samples the held UI target")
		for (descriptor in harness.recording.descriptors) {
			assertSame(
				destination.colorTextureView,
				descriptor.colorAttachments().single()!!.textureView(),
				"both passes write the distinct destination target",
			)
		}
	}

	@Test
	fun `the window closes before the composite runs`() {
		val harness = Harness()
		harness.phase.begin(HeadlessRenderTarget(outputWidth, outputHeight))

		harness.phase.endFrame()

		assertEquals(listOf(false), harness.phaseOpenAtComposite, "the composite never runs while the window answers the UI target")
	}

	@Test
	fun `a frame whose GUI window never opened composites nothing`() {
		val harness = Harness()

		harness.phase.endFrame()

		assertFalse(harness.phase.isOpen)
		assertTrue(harness.recording.pipelines.isEmpty(), "a menu frame leaves the main target untouched")
	}

	@Test
	fun `a frame whose window failed to open composites nothing`() {
		val harness = Harness()
		harness.phase.begin(HeadlessRenderTarget(0, 0))

		harness.phase.endFrame()

		assertFalse(harness.phase.isOpen)
		assertTrue(harness.recording.pipelines.isEmpty(), "a degenerate frame leaves the main target untouched")
	}

	private class Harness {
		val allocated = mutableListOf<HeadlessRenderTarget>()
		val released = mutableListOf<HeadlessRenderTarget>()
		val recording = Recording()
		private val frameComposite = UiComposite(sampler = { FakeSampler() })
		val phaseOpenAtComposite = mutableListOf<Boolean>()

		var phase: UiPhase

		init {
			phase = UiPhase(
				target = UiTarget(
					allocate = { width, height -> HeadlessRenderTarget(width, height).also(allocated::add) },
					release = { released += it as HeadlessRenderTarget },
				),
				encoder = { recording.encoder() },
				composite = {
					phaseOpenAtComposite += phase.isOpen
					frameComposite
				},
			)
		}
	}

	/** Render target with fake GPU textures and views, so lifetime and passes are testable off the render thread. */
	private class HeadlessRenderTarget(
		width: Int,
		height: Int,
	) : RenderTarget("fake-ui", true, GpuFormat.RGBA8_UNORM) {
		init {
			this.width = width
			this.height = height
			colorTexture = FakeTexture(GpuFormat.RGBA8_UNORM, width, height)
			depthTexture = FakeTexture(GpuFormat.D32_FLOAT, width, height)
			colorTextureView = FakeView(colorTexture!!)
			depthTextureView = FakeView(depthTexture!!)
		}

		override fun createBuffers(width: Int, height: Int) {
			this.width = width
			this.height = height
		}

		override fun destroyBuffers() = Unit
	}

	private class FakeTexture(format: GpuFormat, width: Int, height: Int) :
		GpuTexture(USAGE_RENDER_ATTACHMENT or USAGE_COPY_DST, "fake", format, width, height, 1, 1) {
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

	private class Recording {
		data class Clear(val color: GpuTexture, val colorValue: Vector4fc, val depth: GpuTexture, val depthValue: Double)

		val clears = mutableListOf<Clear>()
		val descriptors = mutableListOf<RenderPassDescriptor>()
		val pipelines = mutableListOf<RenderPipeline>()
		val binds = mutableListOf<Pair<String, GpuTextureView?>>()

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
				{ },
				descriptor.renderArea,
			)
		}
	}

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

		override fun draw(vertexCount: Int, instanceCount: Int, firstVertex: Int, firstInstance: Int) = Unit
		override fun multiDraw(drawParameters: IntBuffer, instanceCount: Int, firstInstance: Int, drawCount: Int) = Unit
		override fun multiDraw(firstVertices: IntBuffer, vertexCounts: IntBuffer, drawCount: Int) = Unit
		override fun drawIndirect(commands: GpuBufferSlice, drawCount: Int) = Unit
		override fun writeTimestamp(pool: GpuQueryPool, index: Int) = Unit
	}

	/** The command-encoder backend: records the transparent clear; every other call is unreachable. */
	private class RecordingCommandBackend(private val recording: Recording) : CommandEncoderBackend {
		override fun submit() = Unit
		override fun transientMemory(): TransientMemory = throw UnsupportedOperationException("UI tests never allocate transient memory")
		override fun createRenderPass(descriptor: RenderPassDescriptor): RenderPassBackend =
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
		override fun createCommandEncoder(): CommandEncoderBackend = throw UnsupportedOperationException("UI tests never create encoders")
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
