package me.snowmii.dlss.ui

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.buffers.GpuFence
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline
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
import com.mojang.blaze3d.systems.RenderPassDescriptor
import com.mojang.blaze3d.systems.TransientMemory
import com.mojang.blaze3d.textures.AddressMode
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuSampler
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import java.nio.ByteBuffer
import java.util.OptionalDouble
import java.util.function.Supplier
import me.snowmii.dlss.client.ClientRuntime
import org.joml.Vector4fc
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The GUI window: what `GameRenderer.mainRenderTarget()` answers while [UiPhase] is open, what
 * every other moment sees, and the lifecycle of the transparent full-resolution target behind
 * it. The getter seam's world-over-UI precedence is proven at the [ClientRuntime] resolver the
 * mixin delegates to; everything else is driven off the render thread through injected
 * allocate/release pairs and a recording command encoder whose clear calls record.
 */
class UiPhaseTest {
	private val outputWidth = 2560
	private val outputHeight = 1440

	@Test
	fun `begin opens the GUI window against a full-size transparent UI target`() {
		val harness = Harness()
		val mainTarget = FakeTarget(outputWidth, outputHeight)

		harness.phase.begin(mainTarget)

		assertTrue(harness.phase.isOpen)
		val held = harness.phase.uiTargetOverride
		assertSame(harness.allocated.single(), held, "the getter answers the held UI target")
		assertEquals(outputWidth, held!!.width, "the UI target is full-resolution")
		assertEquals(outputHeight, held.height)
	}

	@Test
	fun `outside the GUI window the getter answers the vanilla target`() {
		val harness = Harness()
		val mainTarget = FakeTarget(outputWidth, outputHeight)

		assertNull(harness.phase.uiTargetOverride)
		harness.phase.begin(mainTarget)
		harness.phase.end()

		assertFalse(harness.phase.isOpen)
		assertNull(harness.phase.uiTargetOverride, "the vanilla target answers again once the window closes")
		assertTrue(harness.released.isEmpty(), "closing the window does not release the held target")
	}

	@Test
	fun `begin clears the held target for the frame`() {
		val harness = Harness()
		val mainTarget = FakeTarget(outputWidth, outputHeight)
		harness.phase.begin(mainTarget)

		val clear = harness.recording.clears.single()
		val held = harness.phase.uiTargetOverride!!
		assertSame(held.colorTexture, clear.color, "the frame's clear empties the held UI target")
		assertSame(held.depthTexture, clear.depth)
	}

	@Test
	fun `the UI target is reused across frames while the window size holds`() {
		val harness = Harness()
		val mainTarget = FakeTarget(outputWidth, outputHeight)

		harness.phase.begin(mainTarget)
		harness.phase.end()
		harness.phase.begin(mainTarget)
		harness.phase.end()

		assertEquals(1, harness.allocated.size, "the target survives between windows")
		assertEquals(2, harness.recording.clears.size, "every window clears the target for its frame")
		assertTrue(harness.released.isEmpty())
	}

	@Test
	fun `a window resize reallocates the UI target at the new window size`() {
		val harness = Harness()

		harness.phase.begin(FakeTarget(outputWidth, outputHeight))
		harness.phase.end()
		harness.phase.begin(FakeTarget(1920, 1080))

		val held = harness.phase.uiTargetOverride!!
		assertEquals(1920, held.width)
		assertEquals(1080, held.height)
		assertEquals(listOf(harness.allocated[0]), harness.released, "the old target is released on the resize")
		assertEquals(2, harness.allocated.size)
	}

	@Test
	fun `a degenerate main target never opens the window`() {
		val harness = Harness()

		harness.phase.begin(FakeTarget(0, 0))

		assertFalse(harness.phase.isOpen)
		assertNull(harness.phase.uiTargetOverride)
		assertTrue(harness.allocated.isEmpty(), "no target is allocated for a degenerate frame")
		assertTrue(harness.recording.clears.isEmpty())
	}

	@Test
	fun `a window abandoned by a failed frame is dropped by the next one`() {
		val harness = Harness()
		val mainTarget = FakeTarget(outputWidth, outputHeight)
		harness.phase.begin(mainTarget)
		// No end(): GuiRenderer.render threw between head and tail, so the window never closed.
		harness.phase.begin(mainTarget)

		assertTrue(harness.phase.isOpen)
		assertSame(harness.allocated.single(), harness.phase.uiTargetOverride, "the fresh window holds the same target")
	}

	@Test
	fun `close drops the window and releases the UI target`() {
		val harness = Harness()
		harness.phase.begin(FakeTarget(outputWidth, outputHeight))

		harness.phase.close()

		assertFalse(harness.phase.isOpen)
		assertNull(harness.phase.uiTargetOverride)
		assertEquals(listOf(harness.allocated.single()), harness.released)
	}

	@Test
	fun `the world phase override wins over the GUI window and vanilla answers outside both`() {
		val world = FakeTarget(1280, 720)
		val ui = FakeTarget(outputWidth, outputHeight)

		assertSame(world, ClientRuntime.resolveTargetOverride(world, ui), "an open world phase beats the GUI window")
		assertSame(ui, ClientRuntime.resolveTargetOverride(null, ui), "the GUI window answers when no world phase is open")
		assertNull(ClientRuntime.resolveTargetOverride(null, null), "outside both windows the vanilla target answers")
	}

	/** The phase under test plus every resource it allocates, releases, or clears. */
	private class Harness {
		val allocated = mutableListOf<FakeTarget>()
		val released = mutableListOf<FakeTarget>()
		val recording = Recording()
		val phase = UiPhase(
			target = UiTarget(
				allocate = { width, height -> FakeTarget(width, height).also(allocated::add) },
				release = { released += it as FakeTarget },
			),
			encoder = { recording.encoder() },
		)
	}

	/** Render target with fake GPU textures and views, so lifetime and clears are testable off the render thread. */
	private class FakeTarget(
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
		GpuTexture(GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_COPY_DST, "fake", format, width, height, 1, 1) {
		override fun close() = Unit
		override fun isClosed() = false
	}

	private class FakeView(texture: GpuTexture) : GpuTextureView(texture, 0, 1) {
		override fun close() = Unit
		override fun isClosed() = false
	}

	/** Records every clear the production code drives. */
	private class Recording {
		data class Clear(val color: GpuTexture, val colorValue: Vector4fc, val depth: GpuTexture, val depthValue: Double)

		val clears = mutableListOf<Clear>()

		fun encoder() = RecordingEncoder(FakeGpuDeviceBackend(), RecordingCommandBackend(this))
	}

	/** The recording encoder: never creates passes - the UI window only clears. */
	private class RecordingEncoder(
		deviceBackend: GpuDeviceBackend,
		commandBackend: CommandEncoderBackend,
	) : CommandEncoder(null, deviceBackend, commandBackend) {
		override fun createRenderPass(descriptor: RenderPassDescriptor): RenderPass =
			throw UnsupportedOperationException("UI phase tests never create render passes")
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

	/** The device-info side of the fake device the CommandEncoder constructor requires; never driven. */
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
		override fun precompilePipeline(pipeline: com.mojang.blaze3d.pipeline.RenderPipeline, shaderSource: ShaderSource?): CompiledRenderPipeline =
			throw UnsupportedOperationException("UI tests never compile pipelines")
		override fun clearPipelineCache() = Unit
		override fun close() = Unit
		override fun createTimestampQueryPool(size: Int): GpuQueryPool = throw UnsupportedOperationException("UI tests never create query pools")
		override fun getTimestampNow(): Long = 0L
		override fun getDeviceInfo(): DeviceInfo = info
	}
}
