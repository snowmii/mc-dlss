package me.snowmii.dlss

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DlssSceneTargetTest {
	private val output = DlssDimensions(2560, 1440)
	private val render = DlssDimensions(1707, 960)

	private val allocated = mutableListOf<FakeTarget>()
	private val released = mutableListOf<FakeTarget>()

	private fun sceneTarget() = DlssSceneTarget(
		allocate = { width, height -> FakeTarget(width, height).also(allocated::add) },
		release = { released += it as FakeTarget },
	)

	@Test
	fun `dlss route allocates one scene target at render dimensions and reuses it`() {
		val scene = sceneTarget()
		val route = router().route(true, output)

		val first = scene.acquire(route)
		val second = scene.acquire(router().route(true, output))

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
		val first = scene.acquire(router(render).route(true, output))

		val second = scene.acquire(router(DlssDimensions(1280, 720)).route(true, output))

		assertNotSame(first, second)
		assertEquals(listOf(first), released)
		assertEquals(2, allocated.size)
		assertEquals(DlssDimensions(1280, 720), scene.currentDimensions)
	}

	@Test
	fun `vanilla route holds no scene target and releases any held one`() {
		val scene = sceneTarget()
		val held = scene.acquire(router().route(true, output))

		val vanilla = scene.acquire(WorldTargetRouter(disabledSession(), render).route(true, output))

		assertNull(vanilla)
		assertNull(scene.current)
		assertNull(scene.currentDimensions)
		assertEquals(listOf(held), released)
	}

	@Test
	fun `close releases the held target exactly once`() {
		val scene = sceneTarget()
		val held = scene.acquire(router().route(true, output))

		scene.close()
		scene.close()

		assertEquals(listOf(held), released)
		assertNull(scene.current)
	}

	@Test
	fun `vanilla main target is never resized or released by scene routing`() {
		val mainTarget = FakeTarget(output.width, output.height)
		val scene = sceneTarget()

		scene.acquire(router().route(true, output))
		scene.acquire(router(DlssDimensions(1280, 720)).route(true, output))
		scene.acquire(WorldTargetRouter(disabledSession(), render).route(true, output))
		scene.close()

		assertEquals(output.width, mainTarget.width)
		assertEquals(output.height, mainTarget.height)
		assertFalse(released.contains(mainTarget))
		assertFalse(allocated.contains(mainTarget))
	}

	private fun router(renderDimensions: DlssDimensions = render) =
		WorldTargetRouter(readySession(), renderDimensions)

	private fun readySession(): DlssSession = session(enabled = true).also {
		check(it.markReadyAfterNativeStartup())
	}

	private fun disabledSession(): DlssSession = session(enabled = false)

	private fun session(enabled: Boolean) = DlssSession(
		DlssStartupConfig(
			enabled = enabled,
			qualityMode = DlssQualityMode.QUALITY,
			outputDimensions = output,
			sdkPath = null,
			nativeLibraryPath = null,
			dataPath = null,
			warnings = emptyList(),
		),
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
