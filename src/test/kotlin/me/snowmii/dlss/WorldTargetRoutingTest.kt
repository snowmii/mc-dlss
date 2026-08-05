package me.snowmii.dlss

import org.junit.jupiter.api.Assertions.assertEquals
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
