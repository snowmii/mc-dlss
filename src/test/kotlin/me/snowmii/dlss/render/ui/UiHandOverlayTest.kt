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
import me.snowmii.dlss.render.ui.UiPhase
import me.snowmii.dlss.render.ui.UiTarget
import java.nio.ByteBuffer
import org.joml.Vector4fc
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Hand window: while `GameRenderer.renderItemInHand` is bracketed (HEAD→TAIL), draw-time
 * `OutputTarget.MAIN_TARGET` answers the transparent full-resolution UI target; screen
 * effects and the 3D crosshair after in `renderLevel` stay on vanilla main.
 *
 * Hand always clears (first UI window); GUI clears only if hand did not, and consumes the
 * handoff so the next frame clears again. A window whose vanilla draw gate closed is an
 * empty clear.
 */
class UiHandOverlayTest {
	private val outputWidth = 2560
	private val outputHeight = 1440

	@Test
	fun `the hand window opens at full resolution and closes back to vanilla`() {
		val harness = Harness()
		val mainTarget = HeadlessRenderTarget(outputWidth, outputHeight)

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
		harness.phase.beginHand(HeadlessRenderTarget(outputWidth, outputHeight))

		val clear = harness.recording.clears.single()
		val held = harness.phase.uiTargetOverride!!
		assertSame(held.colorTexture, clear.color, "the hand window's clear empties the held UI target")
		assertSame(held.depthTexture, clear.depth)
	}

	@Test
	fun `hand then GUI clears the UI target exactly once per frame`() {
		val harness = Harness()
		val mainTarget = HeadlessRenderTarget(outputWidth, outputHeight)

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
		val mainTarget = HeadlessRenderTarget(outputWidth, outputHeight)

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
		val mainTarget = HeadlessRenderTarget(outputWidth, outputHeight)
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
		val mainTarget = HeadlessRenderTarget(outputWidth, outputHeight)
		harness.phase.beginHand(mainTarget)
		// No end(): a leaked hand window must not answer the UI target for the GUI or later callers.
		harness.phase.begin(mainTarget)

		assertTrue(harness.phase.isOpen)
		assertSame(harness.allocated.single(), harness.phase.uiTargetOverride)
		assertEquals(1, harness.recording.clears.size, "the GUI window reuses the hand window's frame clear")
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

	/** Render target with fake GPU textures and views, so lifetime and clears are testable off the render thread. */
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

	/** 26.3: [GpuTexture]/[GpuTextureView] are plain interfaces; the fakes implement them directly. */
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

	/** 26.3: [CommandEncoder] is a plain interface; the fake implements it directly and only ever clears. */
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
