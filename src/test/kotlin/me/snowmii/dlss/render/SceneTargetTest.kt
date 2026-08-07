package me.snowmii.dlss.render
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.session.DlssFrameRoute
import me.snowmii.dlss.session.DlssFrameDecision

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SceneTargetTest {
	private val output = DlssDimensions(2560, 1440)
	private val render = DlssDimensions(1707, 960)

	private val allocated = mutableListOf<FakeTarget>()
	private val released = mutableListOf<FakeTarget>()

	private fun sceneTarget() = SceneTarget(
		allocate = { width, height -> FakeTarget(width, height).also(allocated::add) },
		release = { released += it as FakeTarget },
	)

	@Test
	fun `dlss route allocates one scene target at render dimensions and reuses it`() {
		val scene = sceneTarget()
		val route = dlssRoute()

		val first = scene.acquire(route)
		val second = scene.acquire(dlssRoute())

		assertSame(first, second)
		assertEquals(1, allocated.size)
		assertEquals(render, scene.currentDimensions)
		assertEquals(render.width, first!!.width)
		assertEquals(render.height, first.height)
		assertTrue(released.isEmpty())
	}

	@Test
	fun `changed render dimensions release the old target before allocating the new one`() {
		val scene = sceneTarget()
		val first = scene.acquire(dlssRoute(render))

		val second = scene.acquire(dlssRoute(DlssDimensions(1280, 720)))

		assertNotSame(first, second)
		assertEquals(listOf(first), released)
		assertEquals(2, allocated.size)
		assertEquals(DlssDimensions(1280, 720), scene.currentDimensions)
	}

	@Test
	fun `vanilla route holds no scene target and releases any held one`() {
		val scene = sceneTarget()
		val held = scene.acquire(dlssRoute())

		val vanilla = scene.acquire(vanillaRoute())

		assertNull(vanilla)
		assertNull(scene.current)
		assertNull(scene.currentDimensions)
		assertEquals(listOf(held), released)
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
		val mainTarget = FakeTarget(output.width, output.height)
		val scene = sceneTarget()

		scene.acquire(dlssRoute())
		scene.acquire(dlssRoute(DlssDimensions(1280, 720)))
		scene.acquire(vanillaRoute())
		scene.close()

		assertEquals(output.width, mainTarget.width)
		assertEquals(output.height, mainTarget.height)
		assertFalse(released.contains(mainTarget))
		assertFalse(allocated.contains(mainTarget))
	}

	private fun dlssRoute(renderDimensions: DlssDimensions = render) = WorldTargetRoute(
		frame = DlssFrameDecision(DlssFrameRoute.DLSS, "test"),
		worldDimensions = renderDimensions,
		mainTargetDimensions = output,
	)

	private fun vanillaRoute() = WorldTargetRoute(
		frame = DlssFrameDecision(DlssFrameRoute.VANILLA, "test"),
		worldDimensions = output,
		mainTargetDimensions = output,
	)

	/** Render target with no GPU buffers, so scene-target lifetime is testable off the render thread. */
	private class FakeTarget(width: Int, height: Int) : RenderTarget("fake", true, GpuFormat.RGBA8_UNORM) {
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
