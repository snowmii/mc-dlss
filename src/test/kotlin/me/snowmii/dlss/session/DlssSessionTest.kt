package me.snowmii.dlss.session
import me.snowmii.streamline.PresentTarget
import me.snowmii.streamline.MotionRequest
import me.snowmii.streamline.Dimensions
import me.snowmii.dlss.bridge.NativeApi
import me.snowmii.dlss.bridge.NativeException
import me.snowmii.streamline.ImageBinding
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.streamline.EvaluationImages
import me.snowmii.streamline.FrameTimings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Path

class DlssSessionTest {
	private val output = Dimensions(2560, 1440)

	@Test
	fun disabledConfigurationAlwaysUsesVanilla() {
		val session = DlssSession(config(enabled = false))
		val native = FakeNative()

		assertEquals(DlssSessionState.DISABLED, session.state)
		assertEquals(DlssFrameRoute.VANILLA, session.beginFrame(true, output).route)
		assertNull(LifecycleAdapter(session, native).initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")))
		assertEquals(0, native.initializeCalls)
	}

	@Test
	fun nativeMustBeReadyBeforeNormalWorldFrameUsesDlss() {
		val session = DlssSession(config())

		assertEquals(DlssSessionState.WAITING_FOR_VULKAN, session.state)
		assertEquals(DlssFrameRoute.VANILLA, session.beginFrame(true, output).route)
		assertTrue(
			LifecycleAdapter(session, FakeNative()).initialize(
				1L,
				2L,
				3L,
				Path.of("sdk"),
				Path.of("data"),
			) != null,
		)
		assertEquals(DlssFrameRoute.DLSS, session.beginFrame(true, output).route)
	}

	@Test
	fun unsupportedFramesAndOutputSizesStayVanilla() {
		val session = DlssSession(config())
		assertTrue(
			LifecycleAdapter(session, FakeNative()).initialize(
				1L,
				2L,
				3L,
				Path.of("sdk"),
				Path.of("data"),
			) != null,
		)

		assertEquals(DlssFrameRoute.VANILLA, session.beginFrame(false, output).route)
		assertEquals(
			DlssFrameRoute.VANILLA,
			session.beginFrame(true, Dimensions(1920, 1080)).route,
		)
	}

	@Test
	fun firstNativeFailureLatchesVanillaFallbackAndDiagnostic() {
		val diagnostics = mutableListOf<String>()
		val session = DlssSession(config(), diagnostics::add)
		val failure = DlssNativeFailure(
			stage = DlssNativeStage.EVALUATE,
			resultCode = 0xC0000001.toInt(),
			detail = "unsupported-image",
		)

		assertTrue(session.latchFailure(failure))
		assertFalse(session.latchFailure(DlssNativeFailure(DlssNativeStage.RESET, 7)))
		assertEquals(DlssSessionState.FALLBACK_LATCHED, session.state)
		assertEquals(DlssFrameRoute.VANILLA, session.beginFrame(true, output).route)
		assertEquals(
			"DLSS fallback latched: stage=evaluate result=0xC0000001 detail=unsupported-image",
			diagnostics.single(),
		)
	}

	@Test
	fun closedSessionStaysVanilla() {
		val session = DlssSession(config())
		val native = FakeNative()
		session.close()

		assertEquals(DlssSessionState.CLOSED, session.state)
		assertEquals(DlssFrameRoute.VANILLA, session.beginFrame(true, output).route)
		assertNull(LifecycleAdapter(session, native).initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")))
		assertEquals(0, native.initializeCalls)
	}

	/**
	 * Every native stage latches the same way, so the stage is the parameter rather than the test:
	 * the exact stage and result code reach the session, the route falls back to vanilla, and the
	 * failed stage is never called a second time.
	 *
	 * The stage before the failing one still ran (initialize before query, query before configure),
	 * which is what [FailureCase.calls] counts: exactly one attempt, never a retry.
	 */
	@ParameterizedTest
	@MethodSource("failureCases")
	fun aFailedNativeStageLatchesItsExactStageAndIsNeverRetried(case: FailureCase) {
		val diagnostics = mutableListOf<String>()
		val native = case.native()
		val session = DlssSession(config(), diagnostics::add)
		val adapter = LifecycleAdapter(session, native)

		case.drive(adapter)
		case.drive(adapter)

		assertEquals(case.expected, session.failure)
		assertEquals(DlssSessionState.FALLBACK_LATCHED, session.state)
		assertEquals(DlssFrameRoute.VANILLA, session.beginFrame(true, output).route)
		assertEquals(case.diagnostic, diagnostics.single())
		assertEquals(1, case.calls(native), "the failed stage must not be attempted twice")
	}

	@Test
	fun aFailedQueryNeverReachesConfigure() {
		val native = FakeNative(queryResult = 23)
		val adapter = LifecycleAdapter(DlssSession(config()), native)

		assertNull(adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")))

		assertEquals(0, native.configureCalls)
	}

	@Test
	fun evaluateForwardsCompleteResourceMetadata() {
		val native = FakeNative()
		val session = DlssSession(config())
		val adapter = LifecycleAdapter(session, native)
		assertTrue(adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")) != null)
		val request = EvaluationRequest.builder()
			.commandBuffer(10L)
			.color(ImageBinding(11L, 12L, 13))
			.depth(ImageBinding(21L, 22L, 23))
			.build()

		assertTrue(adapter.evaluate(request))
		// The adapter stamps the configured render size on the way through; everything the caller
		// described crosses untouched.
		assertEquals(
			EvaluationRequest.builder()
				.commandBuffer(10L)
				.color(ImageBinding(11L, 12L, 13))
				.depth(ImageBinding(21L, 22L, 23))
				.renderDimensions(Dimensions(1280, 720))
				.build(),
			native.lastEvaluation,
		)
	}

	class FakeNative(
		private val initializeResult: Int = 1,
		private val queryResult: Int? = null,
		private val configureResult: Int = 1,
		private val evaluateResult: Int = 1,
		private val acquireImagesResult: Int? = null,
		private val releaseImagesResult: Int = 1,
	) : NativeApi {
		var initializeCalls = 0
		var queryCalls = 0
		var configureCalls = 0
		var evaluateCalls = 0
		var acquireImageCalls = 0
		var releaseImageCalls = 0
		var writeMotionCalls = 0
		var presentOutputCalls = 0
		var lastEvaluation: EvaluationRequest? = null
		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		): Int {
			initializeCalls++
			return initializeResult
		}

		override fun queryOptimalDimensions(
			outputWidth: Int,
			outputHeight: Int,
			qualityMode: Int,
		): Dimensions {
			queryCalls++
			queryResult?.let { throw NativeException("query-dimensions", it) }
			return Dimensions(1280, 720)
		}

		override fun configure(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		): Int {
			configureCalls++
			return configureResult
		}

		override fun acquireImages(): EvaluationImages {
			acquireImageCalls++
			acquireImagesResult?.let { throw NativeException("acquire-images", it) }
			return EvaluationImages(
				ImageBinding(0x1002, 0x1001, 83),
				ImageBinding(0x2002, 0x2001, 37),
			)
		}

		override fun releaseImages(): Int {
			releaseImageCalls++
			return releaseImagesResult
		}

		override fun waitDeviceIdle(): Int = NativeApi.SUCCESS_RESULT

		override fun frameTimings(): FrameTimings? = null

		// The session lifecycle never records GPU work, so the motion pass only has to exist
		// here for the interface to be implemented.
		override fun writeMotion(request: MotionRequest): Int {
			writeMotionCalls++
			return NativeApi.SUCCESS_RESULT
		}

		override fun presentOutput(target: PresentTarget): Int {
			presentOutputCalls++
			return NativeApi.SUCCESS_RESULT
		}

		override fun evaluate(request: EvaluationRequest): Int {
			evaluateCalls++
			lastEvaluation = request
			return evaluateResult
		}
	}

	private fun config(enabled: Boolean = true): DlssStartupConfig = DlssStartupConfig(
		enabled = enabled,
		qualityMode = SRMode.QUALITY,
		outputDimensions = output,
		sdkPath = null,
		nativeLibraryPath = null,
		dataPath = null,
		warnings = emptyList(),
	)

	/** One native stage rigged to fail, with the way the adapter reaches it. */
	data class FailureCase(
		private val name: String,
		val native: () -> FakeNative,
		val drive: (LifecycleAdapter) -> Unit,
		val calls: (FakeNative) -> Int,
		val expected: DlssNativeFailure,
		val diagnostic: String,
	) {
		override fun toString() = name
	}

	companion object {
		private val SDK: Path = Path.of("sdk")
		private val DATA: Path = Path.of("data")

		@JvmStatic
		fun failureCases(): List<FailureCase> = listOf(
			FailureCase(
				"initialize",
				{ FakeNative(initializeResult = 17) },
				{ it.initialize(1L, 2L, 3L, SDK, DATA) },
				{ it.initializeCalls },
				DlssNativeFailure(DlssNativeStage.INITIALIZE, 17),
				"DLSS fallback latched: stage=initialize result=0x00000011",
			),
			FailureCase(
				"query-dimensions",
				{ FakeNative(queryResult = 23) },
				{ it.initialize(1L, 2L, 3L, SDK, DATA) },
				{ it.queryCalls },
				DlssNativeFailure(DlssNativeStage.QUERY_DIMENSIONS, 23),
				"DLSS fallback latched: stage=query-dimensions result=0x00000017",
			),
			FailureCase(
				"configure",
				{ FakeNative(configureResult = 31) },
				{ it.initialize(1L, 2L, 3L, SDK, DATA) },
				{ it.configureCalls },
				DlssNativeFailure(DlssNativeStage.CONFIGURE, 31),
				"DLSS fallback latched: stage=configure result=0x0000001F",
			),
			FailureCase(
				"evaluate",
				{ FakeNative(evaluateResult = 41) },
				{
					// The evaluate stage is only reachable through a session that started; the
					// second drive's initialize is refused by the latch, as the retry must be.
					it.initialize(1L, 2L, 3L, SDK, DATA)
					it.evaluate(EvaluationRequest())
				},
				{ it.evaluateCalls },
				DlssNativeFailure(DlssNativeStage.EVALUATE, 41),
				"DLSS fallback latched: stage=evaluate result=0x00000029",
			),
		)
	}
}
