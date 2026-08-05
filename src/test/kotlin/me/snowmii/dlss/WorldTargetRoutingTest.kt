package me.snowmii.dlss

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.api.Test

class WorldTargetRoutingTest {
	private val output = DlssDimensions(2560, 1440)
	private val render = DlssDimensions(1707, 960)

	@Test
	fun `eligible world frame uses queried render dimensions and keeps main target full sized`() {
		val session = readySession()

		val route = WorldTargetRouter(session, render).route(true, output)

		assertEquals(DlssFrameRoute.DLSS, route.frame.route)
		assertEquals(render, route.worldDimensions)
		assertEquals(output, route.mainTargetDimensions)
	}

	@ParameterizedTest
	@MethodSource("vanillaCases")
	fun `vanilla routes keep world and main target at output dimensions`(case: VanillaCase) {
		val route = WorldTargetRouter(case.session(), render).route(case.normalInWorldFrame, case.outputDimensions)

		assertEquals(DlssFrameRoute.VANILLA, route.frame.route)
		assertEquals(case.outputDimensions, route.worldDimensions)
		assertEquals(case.outputDimensions, route.mainTargetDimensions)
	}

	@Test
	fun `the world phase renders an eligible frame at render dimensions into a target that is not the main one`() {
		val session = readySession()
		val main = FakeTarget(output.width, output.height)
		val phase = worldPhase(session)

		val worldTarget = phase.begin(normalInWorldFrame = true, mainTarget = main)
		phase.end()

		assertNotSame(main, worldTarget)
		assertEquals(render.width, worldTarget.width)
		assertEquals(render.height, worldTarget.height)
		assertEquals(output.width, main.width)
		assertEquals(output.height, main.height)
	}

	@ParameterizedTest
	@MethodSource("vanillaCases")
	fun `the world phase renders every vanilla route into the main target at output dimensions`(case: VanillaCase) {
		val main = FakeTarget(case.outputDimensions.width, case.outputDimensions.height)
		val phase = worldPhase(case.session())

		val worldTarget = phase.begin(case.normalInWorldFrame, main)
		phase.end()

		assertSame(main, worldTarget)
		assertEquals(case.outputDimensions.width, main.width)
		assertEquals(case.outputDimensions.height, main.height)
	}

	/**
	 * The routing seam as the render loop reaches it, with presentation and the sky reset
	 * stubbed out. Startup is pre-resolved so only the routing decision is under test.
	 */
	private fun worldPhase(session: DlssSession) = DlssWorldPhase(
		runtime = DlssRenderRuntime(
			session = session,
			sceneTarget = DlssSceneTarget(
				allocate = { width, height -> FakeTarget(width, height) },
				release = {},
			),
			startup = { render },
		),
		present = { _, _ -> },
		onWorldTargetChanged = {},
	)

	private fun readySession(): DlssSession = configuredSession().also {
		check(it.markReadyAfterNativeStartup())
	}

	private fun configuredSession(enabled: Boolean = true) = DlssSession(
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

	/** Render target with no GPU buffers, so routing is testable off the render thread. */
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

	data class VanillaCase(
		val session: () -> DlssSession,
		val normalInWorldFrame: Boolean,
		val outputDimensions: DlssDimensions,
	)

	companion object {
		@JvmStatic
		fun vanillaCases(): List<VanillaCase> {
			val expectedOutput = DlssDimensions(2560, 1440)
			fun session(enabled: Boolean = true, ready: Boolean = true): DlssSession {
				val value = DlssSession(
					DlssStartupConfig(enabled, DlssQualityMode.QUALITY, expectedOutput, null, null, null, emptyList()),
				)
				if (enabled && ready) check(value.markReadyAfterNativeStartup())
				return value
			}
			return listOf(
				VanillaCase({ session(enabled = false) }, true, expectedOutput),
				VanillaCase({ session(ready = false) }, true, expectedOutput),
				VanillaCase({ session() }, false, expectedOutput),
				VanillaCase({ session() }, true, DlssDimensions(1920, 1080)),
				VanillaCase({ session().also { it.latchFailure(DlssNativeFailure(DlssNativeStage.EVALUATE, 1)) } }, true, expectedOutput),
			)
		}
	}
}
