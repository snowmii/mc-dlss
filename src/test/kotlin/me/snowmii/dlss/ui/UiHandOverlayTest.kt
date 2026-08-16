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
import org.joml.Vector4fc
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The hand window: while `GameRenderer.renderItemInHand` is bracketed, draw-time
 * `OutputTarget.MAIN_TARGET` resolution answers the transparent full-resolution UI target, and
 * the frame's UI clear happens exactly once across the hand window and the GUI window that
 * follows it.
 *
 * The hand window is a second window on [UiPhase], opened by the hand mixin's HEAD inject and
 * closed by its TAIL inject - the same getter override the GUI window uses, so
 * `mainRenderTarget()` answers the UI target while hand features draw and the vanilla main
 * target for the screen effects and 3D crosshair that run right after the hand in
 * `GameRenderer.renderLevel`. Hand drawing is gated inside vanilla's method on HUD visibility,
 * camera type, and game mode, so a window whose draw gate closed is just an empty clear.
 *
 * The frame's clear ownership: the hand window always clears (it is the frame's first UI
 * window), the GUI window clears only when no hand window ran first, and the GUI window
 * consumes the handoff so the next frame clears again.
 */
class UiHandOverlayTest {
	private val outputWidth = 2560
	private val outputHeight = 1440

	@Test
	fun `the hand window opens at full resolution and closes back to vanilla`() {
		val harness = Harness()
		val mainTarget = FakeTarget(outputWidth, outputHeight)

		harness.phase.beginHand(mainTarget)

		assertTrue(harness.phase.isOpen)
		val held = harness.phase.uiTargetOverride
		assertSame(harness.allocated.single(), held, "draw-time target resolution answers the held UI target")
		assertEquals(outputWidth, held!!.width, "the hand draws at full resolution")
		assertEquals(outputHeight, held.height)

		harness.phase.end()

		assertFalse(harness.phase.isOpen)
		assertNull(harness.phase.uiTargetOverride, "screen effects and the 3D crosshair see the vanilla main target again")
		assertTrue(harness.released.isEmpty(), "closing the hand window does not release the held target")
	}

	@Test
	fun `the hand window clears the UI target for the frame`() {
		val harness = Harness()
		harness.phase.beginHand(FakeTarget(outputWidth, outputHeight))

		val clear = harness.recording.clears.single()
		val held = harness.phase.uiTargetOverride!!
		assertSame(held.colorTexture, clear.color, "the hand window's clear empties the held UI target")
		assertSame(held.depthTexture, clear.depth)
	}

	@Test
	fun `hand then GUI clears the UI target exactly once per frame`() {
		val harness = Harness()
		val mainTarget = FakeTarget(outputWidth, outputHeight)

		harness.phase.beginHand(mainTarget)
		harness.phase.end()
		harness.phase.begin(mainTarget)

		assertEquals(1, harness.recording.clears.size, "the GUI window does not clear a target the hand window already cleared")
		assertTrue(harness.phase.isOpen, "the GUI window opens on the same clear")
		assertSame(harness.allocated.single(), harness.phase.uiTargetOverride)
	}

	@Test
	fun `the clear-once handoff resets across frames`() {
		val harness = Harness()
		val mainTarget = FakeTarget(outputWidth, outputHeight)

		harness.phase.beginHand(mainTarget)
		harness.phase.end()
		harness.phase.begin(mainTarget)
		harness.phase.end()
		harness.phase.beginHand(mainTarget)
		harness.phase.end()
		harness.phase.begin(mainTarget)

		assertEquals(2, harness.recording.clears.size, "every frame clears exactly once, the GUI window's consumption does not leak")
		assertEquals(1, harness.allocated.size, "the target survives across frames")
	}

	@Test
	fun `a hand window abandoned by a failed frame is dropped by the next one`() {
		val harness = Harness()
		val mainTarget = FakeTarget(outputWidth, outputHeight)
		harness.phase.beginHand(mainTarget)
		// No end(): renderItemInHand threw between head and tail, so the window never closed.
		harness.phase.beginHand(mainTarget)

		assertTrue(harness.phase.isOpen)
		assertSame(harness.allocated.single(), harness.phase.uiTargetOverride, "the fresh window holds the same target")
		assertEquals(2, harness.recording.clears.size, "every hand window clears the target for its own frame")
	}

	@Test
	fun `an open hand window is dropped when the GUI window opens`() {
		val harness = Harness()
		val mainTarget = FakeTarget(outputWidth, outputHeight)
		harness.phase.beginHand(mainTarget)
		// No end(): a leaked hand window must not answer the UI target for the GUI or later callers.
		harness.phase.begin(mainTarget)

		assertTrue(harness.phase.isOpen)
		assertSame(harness.allocated.single(), harness.phase.uiTargetOverride)
		assertEquals(1, harness.recording.clears.size, "the GUI window reuses the hand window's frame clear")
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

	/** The recording encoder: never creates passes - the UI windows only clear. */
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
