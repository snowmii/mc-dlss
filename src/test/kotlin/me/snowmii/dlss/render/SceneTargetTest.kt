package me.snowmii.dlss.render
import me.snowmii.streamline.Dimensions
import me.snowmii.dlss.DlssFrameRoute
import me.snowmii.dlss.DlssFrameDecision

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SceneTargetTest {
	private val output = Dimensions(2560, 1440)
	private val render = Dimensions(1707, 960)

	private val allocated = mutableListOf<HeadlessRenderTarget>()
	private val released = mutableListOf<HeadlessRenderTarget>()

	private fun sceneTarget() = SceneTarget(
		allocate = { width, height -> HeadlessRenderTarget(width, height).also(allocated::add) },
		release = { released += it as HeadlessRenderTarget },
	)

	@Test
	fun `changed render dimensions release the old target before allocating the new one`() {
		val scene = sceneTarget()
		val first = scene.acquire(dlssRoute(render))

		val second = scene.acquire(dlssRoute(Dimensions(1280, 720)))

		assertNotSame(first, second)
		assertEquals(listOf(first), released)
		assertEquals(2, allocated.size)
		assertEquals(Dimensions(1280, 720), scene.currentDimensions)
	}

	@Test
	fun `close releases the held target exactly once`() {
		val scene = sceneTarget()
		val held = scene.acquire(dlssRoute())

		scene.close()
		scene.close()

		assertEquals(listOf(held), released)
		assertNull(scene.current)
	}

	@Test
	fun `vanilla main target is never resized or released by scene routing`() {
		val mainTarget = HeadlessRenderTarget(output.width, output.height)
		val scene = sceneTarget()

		scene.acquire(dlssRoute())
		scene.acquire(dlssRoute(Dimensions(1280, 720)))
		scene.acquire(vanillaRoute())
		scene.close()

		assertEquals(output.width, mainTarget.width)
		assertEquals(output.height, mainTarget.height)
		assertFalse(released.contains(mainTarget))
		assertFalse(allocated.contains(mainTarget))
	}

	private fun dlssRoute(renderDimensions: Dimensions = render) = WorldTargetRoute(
		frame = DlssFrameDecision(DlssFrameRoute.DLSS, "test"),
		worldDimensions = renderDimensions,
		mainTargetDimensions = output,
	)

	private fun vanillaRoute() = WorldTargetRoute(
		frame = DlssFrameDecision(DlssFrameRoute.VANILLA, "test"),
		worldDimensions = output,
		mainTargetDimensions = output,
	)

	private class HeadlessRenderTarget(width: Int, height: Int) : RenderTarget("fake", GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT) {
		init {
			this.width = width
			this.height = height
		}

		override fun createBuffers(width: Int, height: Int) {
			this.width = width
			this.height = height
		}

		override fun destroyBuffers() = Unit
	}
}
