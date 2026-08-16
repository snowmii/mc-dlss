package me.snowmii.dlss.render
import me.snowmii.streamline.Dimensions
import me.snowmii.dlss.session.DlssFrameRoute
import me.snowmii.dlss.session.DlssFrameDecision

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SceneTargetTest {
	private val output = Dimensions(2560, 1440)
	private val render = Dimensions(1707, 960)

	private val allocated = mutableListOf<FakeTarget>()
	private val released = mutableListOf<FakeTarget>()

	private fun sceneTarget() = SceneTarget(
		allocate = { width, height -> FakeTarget(width, height).also(allocated::add) },
		release = { released += it as FakeTarget },
	)

	private fun sceneTargetWithVelocity() = SceneTarget(
		allocate = { width, height -> FakeTarget(width, height).also(allocated::add) },
		release = { released += it as FakeTarget },
		allocateVelocity = { width, height -> FakeTarget(width, height).also(velocityAllocated::add) },
	)

	private val velocityAllocated = mutableListOf<FakeTarget>()

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
	fun `dlss route allocates a scene-sized velocity companion alongside the scene target and reuses it`() {
		val scene = sceneTargetWithVelocity()
		val route = dlssRoute()

		val first = scene.acquire(route)
		val firstVelocity = scene.currentVelocity!!
		val second = scene.acquire(dlssRoute())

		assertSame(first, second)
		assertSame(firstVelocity, scene.currentVelocity)
		assertEquals(1, allocated.size)
		assertEquals(1, velocityAllocated.size)
		assertEquals(render.width, firstVelocity.width)
		assertEquals(render.height, firstVelocity.height)
		assertTrue(released.isEmpty())
	}

	@Test
	fun `changed render dimensions release the velocity companion with the scene target before reallocating`() {
		val scene = sceneTargetWithVelocity()
		val first = scene.acquire(dlssRoute(render))
		val firstVelocity = scene.currentVelocity!!

		val second = scene.acquire(dlssRoute(Dimensions(1280, 720)))

		assertNotSame(first, second)
		assertEquals(listOf(first, firstVelocity), released)
		assertEquals(2, allocated.size)
		assertEquals(2, velocityAllocated.size)
		assertEquals(1280, scene.currentVelocity!!.width)
		assertEquals(720, scene.currentVelocity!!.height)
	}

	@Test
	fun `vanilla route releases the velocity companion with the scene target`() {
		val scene = sceneTargetWithVelocity()
		val held = scene.acquire(dlssRoute())
		val velocity = scene.currentVelocity!!

		val vanilla = scene.acquire(vanillaRoute())

		assertNull(vanilla)
		assertNull(scene.currentVelocity)
		assertEquals(listOf(held, velocity), released)
	}

	@Test
	fun `close releases the velocity companion exactly once`() {
		val scene = sceneTargetWithVelocity()
		val held = scene.acquire(dlssRoute())
		val velocity = scene.currentVelocity!!

		scene.close()
		scene.close()

		assertEquals(listOf(held, velocity), released)
		assertNull(scene.currentVelocity)
	}

	@Test
	fun `velocity companion is always the scene-sized RG16 float payload format`() {
		assertEquals(GpuFormat.RG16_FLOAT, SceneTarget.VELOCITY_FORMAT)
	}

	@Test
	fun `velocity allocation failure releases the scene target exactly once and leaves nothing held`() {
		val scene = SceneTarget(
			allocate = { width, height -> FakeTarget(width, height).also(allocated::add) },
			release = { released += it as FakeTarget },
			allocateVelocity = { _, _ -> error("velocity allocation failed") },
		)

		assertThrows(IllegalStateException::class.java) { scene.acquire(dlssRoute()) }

		assertNull(scene.current)
		assertNull(scene.currentVelocity)
		assertNull(scene.currentDimensions)
		assertEquals(1, allocated.size)
		assertEquals(listOf(allocated.single()), released)
		assertTrue(velocityAllocated.isEmpty())
	}

	@Test
	fun `a failed velocity allocation publishes no partial state for the next acquire`() {
		var failVelocity = true
		val scene = SceneTarget(
			allocate = { width, height -> FakeTarget(width, height).also(allocated::add) },
			release = { released += it as FakeTarget },
			allocateVelocity = { width, height ->
				if (failVelocity) {
					error("velocity allocation failed")
				}
				FakeTarget(width, height).also(velocityAllocated::add)
			},
		)
		assertThrows(IllegalStateException::class.java) { scene.acquire(dlssRoute()) }

		failVelocity = false
		val held = scene.acquire(dlssRoute())

		assertSame(held, scene.current)
		assertEquals(render, scene.currentDimensions)
		assertEquals(2, allocated.size)
		assertEquals(1, velocityAllocated.size)
		// Only the scene orphaned by the failed attempt was released; the fresh pair is still held.
		assertEquals(1, released.size)
	}

	@Test
	fun `vanilla main target is never resized or released by scene routing`() {
		val mainTarget = FakeTarget(output.width, output.height)
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
