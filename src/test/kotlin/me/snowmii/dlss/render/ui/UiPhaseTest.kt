package me.snowmii.dlss.render.ui

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.commands.GpuFence
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.renderpearl.api.commands.CommandEncoder
import com.mojang.renderpearl.api.commands.GpuQueryPool
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.commands.RenderPassDescriptor
import com.mojang.renderpearl.api.buffers.TransientMemory
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import java.nio.ByteBuffer
import me.snowmii.dlss.client.ClientRuntime
import me.snowmii.dlss.render.ui.UiPhase
import me.snowmii.dlss.render.ui.UiTarget
import org.joml.Vector4fc
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UiPhaseTest {
	private val outputWidth = 2560
	private val outputHeight = 1440

	@Test
	fun `begin opens the GUI window against a full-size transparent UI target`() {
		val harness = Harness()
		val mainTarget = HeadlessRenderTarget(outputWidth, outputHeight)

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
		val mainTarget = HeadlessRenderTarget(outputWidth, outputHeight)

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
		val mainTarget = HeadlessRenderTarget(outputWidth, outputHeight)
		harness.phase.begin(mainTarget)

		val clear = harness.recording.clears.single()
		val held = harness.phase.uiTargetOverride!!
		assertSame(held.colorTexture, clear.color, "the frame's clear empties the held UI target")
		assertSame(held.depthTexture, clear.depth)
	}

	@Test
	fun `the UI target is reused across frames while the window size holds`() {
		val harness = Harness()
		val mainTarget = HeadlessRenderTarget(outputWidth, outputHeight)

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

		harness.phase.begin(HeadlessRenderTarget(outputWidth, outputHeight))
		harness.phase.end()
		harness.phase.begin(HeadlessRenderTarget(1920, 1080))

		val held = harness.phase.uiTargetOverride!!
		assertEquals(1920, held.width)
		assertEquals(1080, held.height)
		assertEquals(listOf(harness.allocated[0]), harness.released, "the old target is released on the resize")
		assertEquals(2, harness.allocated.size)
	}

	@Test
	fun `a degenerate main target never opens the window`() {
		val harness = Harness()

		harness.phase.begin(HeadlessRenderTarget(0, 0))

		assertFalse(harness.phase.isOpen)
		assertNull(harness.phase.uiTargetOverride)
		assertTrue(harness.allocated.isEmpty(), "no target is allocated for a degenerate frame")
		assertTrue(harness.recording.clears.isEmpty())
	}

	@Test
	fun `a window abandoned by a failed frame is dropped by the next one`() {
		val harness = Harness()
		val mainTarget = HeadlessRenderTarget(outputWidth, outputHeight)
		harness.phase.begin(mainTarget)
		harness.phase.begin(mainTarget)

		assertTrue(harness.phase.isOpen)
		assertSame(harness.allocated.single(), harness.phase.uiTargetOverride, "the fresh window holds the same target")
	}

	@Test
	fun `close drops the window and releases the UI target`() {
		val harness = Harness()
		harness.phase.begin(HeadlessRenderTarget(outputWidth, outputHeight))

		harness.phase.close()

		assertFalse(harness.phase.isOpen)
		assertNull(harness.phase.uiTargetOverride)
		assertEquals(listOf(harness.allocated.single()), harness.released)
	}

	@Test
	fun `the world phase override wins over the GUI window and vanilla answers outside both`() {
		val world = HeadlessRenderTarget(1280, 720)
		val ui = HeadlessRenderTarget(outputWidth, outputHeight)

		assertSame(world, ClientRuntime.resolveActiveTarget(world, ui), "an open world phase beats the GUI window")
		assertSame(ui, ClientRuntime.resolveActiveTarget(null, ui), "the GUI window answers when no world phase is open")
		assertNull(ClientRuntime.resolveActiveTarget(null, null), "outside both windows the vanilla target answers")
	}

	private class Harness {
		val allocated = mutableListOf<HeadlessRenderTarget>()
		val released = mutableListOf<HeadlessRenderTarget>()
		val recording = Recording()
		val phase = UiPhase(
			target = UiTarget(
				allocate = { width, height -> HeadlessRenderTarget(width, height).also(allocated::add) },
				release = { released += it as HeadlessRenderTarget },
			),
			encoder = { recording.encoder() },
		)
	}

	private class HeadlessRenderTarget(
		width: Int,
		height: Int,
	) : RenderTarget("fake-ui", GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT) {
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

	private class Recording {
		data class Clear(val color: GpuTexture, val colorValue: Vector4fc, val depth: GpuTexture, val depthValue: Double)

		val clears = mutableListOf<Clear>()

		fun encoder() = RecordingEncoder(this)
	}

	private class RecordingEncoder(private val recording: Recording) : CommandEncoder {
		override fun submit() = Unit

		override fun transientMemory(): TransientMemory = throw UnsupportedOperationException("UI tests never allocate transient memory")

		override fun createRenderPass(descriptor: RenderPassDescriptor): RenderPass =
			throw UnsupportedOperationException("UI phase tests never create render passes")

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
}
