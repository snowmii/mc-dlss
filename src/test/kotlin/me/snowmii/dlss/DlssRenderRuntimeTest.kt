package me.snowmii.dlss

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Proves the production owner drives startup exactly once, publishes NGX-queried render
 * dimensions, and hands the world phase the right target for every route.
 */
class DlssRenderRuntimeTest {
	private val output = DlssDimensions(2560, 1440)
	private val render = DlssDimensions(1707, 960)

	private val allocated = mutableListOf<FakeTarget>()
	private val released = mutableListOf<FakeTarget>()
	private var startupCalls = 0

	@Test
	fun `first world phase starts native once and routes to a render-sized scene target`() {
		val session = session(enabled = true)
		val runtime = runtime(session) { markReady(session); render }

		val first = runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = output)
		runtime.endWorldPhase()
		val second = runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = output)

		assertSame(first, second)
		assertEquals(1, startupCalls)
		assertEquals(1, allocated.size)
		assertEquals(render, runtime.renderDimensions)
		assertEquals(render.width, first!!.width)
		assertEquals(render.height, first.height)
		assertEquals(DlssFrameRoute.DLSS, runtime.activeRoute!!.frame.route)
		assertSame(second, runtime.activeWorldTarget)
	}

	@Test
	fun `ending the world phase clears the active target but keeps it allocated`() {
		val session = session(enabled = true)
		val runtime = runtime(session) { markReady(session); render }

		runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = output)
		runtime.endWorldPhase()

		assertNull(runtime.activeWorldTarget)
		assertNull(runtime.activeRoute)
		assertTrue(released.isEmpty())
	}

	@Test
	fun `a vanilla frame routes full resolution and releases the scene target`() {
		val session = session(enabled = true)
		val runtime = runtime(session) { markReady(session); render }
		val held = runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = output)
		runtime.endWorldPhase()

		val vanilla = runtime.beginWorldPhase(normalInWorldFrame = false, outputDimensions = output)

		assertNull(vanilla)
		assertNull(runtime.activeWorldTarget)
		assertEquals(DlssFrameRoute.VANILLA, runtime.activeRoute!!.frame.route)
		assertEquals(output, runtime.activeRoute!!.worldDimensions)
		assertEquals(listOf(held), released)
	}

	@Test
	fun `failed startup never retries and every later frame stays vanilla`() {
		val session = session(enabled = true)
		val runtime = runtime(session) {
			session.latchFailure(DlssNativeFailure(DlssNativeStage.INITIALIZE, 0xBAD00001.toInt()))
			null
		}

		val first = runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = output)
		val second = runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = output)

		assertNull(first)
		assertNull(second)
		assertEquals(1, startupCalls)
		assertTrue(allocated.isEmpty())
		assertNull(runtime.renderDimensions)
		assertEquals(DlssSessionState.FALLBACK_LATCHED, session.state)
	}

	@Test
	fun `startup that returns dimensions without a ready session is not trusted`() {
		val session = session(enabled = false)
		val runtime = runtime(session) { render }

		val target = runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = output)

		assertNull(target)
		assertNull(runtime.renderDimensions)
		assertTrue(allocated.isEmpty())
	}

	@Test
	fun `each eligible world phase advances the jitter sequence exactly once`() {
		val session = session(enabled = true)
		val runtime = runtime(session) { markReady(session); render }
		val expected = DlssJitter(render, output)

		runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = output)
		val first = runtime.activeJitter
		runtime.endWorldPhase()
		runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = output)
		val second = runtime.activeJitter

		assertEquals(expected.advance(), first)
		assertEquals(expected.advance(), second)
		assertEquals(render, first!!.renderDimensions)
	}

	@Test
	fun `a vanilla frame publishes no jitter and restarts the sequence`() {
		val session = session(enabled = true)
		val runtime = runtime(session) { markReady(session); render }
		runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = output)
		val first = runtime.activeJitter
		runtime.endWorldPhase()

		runtime.beginWorldPhase(normalInWorldFrame = false, outputDimensions = output)
		val duringVanilla = runtime.activeJitter
		runtime.endWorldPhase()
		runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = output)

		assertNull(duringVanilla)
		assertEquals(first, runtime.activeJitter)
	}

	@Test
	fun `ending a world phase drops the published jitter`() {
		val session = session(enabled = true)
		val runtime = runtime(session) { markReady(session); render }

		runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = output)
		runtime.endWorldPhase()

		assertNull(runtime.activeJitter)
	}

	@Test
	fun `a session without DLSS never publishes jitter`() {
		val session = session(enabled = true)
		val runtime = runtime(session) {
			session.latchFailure(DlssNativeFailure(DlssNativeStage.INITIALIZE, 0xBAD00001.toInt()))
			null
		}

		runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = output)

		assertNull(runtime.activeJitter)
	}

	@Test
	fun `close releases the scene target and closes the session`() {
		val session = session(enabled = true)
		val runtime = runtime(session) { markReady(session); render }
		val held = runtime.beginWorldPhase(normalInWorldFrame = true, outputDimensions = output)

		runtime.close()

		assertEquals(listOf(held), released)
		assertNull(runtime.activeWorldTarget)
		assertNull(runtime.renderDimensions)
		assertEquals(DlssSessionState.CLOSED, session.state)
	}

	private fun runtime(session: DlssSession, startup: () -> DlssDimensions?) = DlssRenderRuntime(
		session = session,
		sceneTarget = DlssSceneTarget(
			allocate = { width, height -> FakeTarget(width, height).also(allocated::add) },
			release = { released += it as FakeTarget },
		),
		startup = {
			startupCalls++
			startup()
		},
	)

	private fun markReady(session: DlssSession) {
		check(session.markReadyAfterNativeStartup())
	}

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

	/** Render target with no GPU buffers, so runtime lifetime is testable off the render thread. */
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
