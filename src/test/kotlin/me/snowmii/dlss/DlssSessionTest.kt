package me.snowmii.dlss

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class DlssSessionTest {
	private val output = DlssDimensions(2560, 1440)

	@Test
	fun disabledConfigurationAlwaysUsesVanilla() {
		val session = DlssSession(config(enabled = false))
		val native = FakeNative()

		assertEquals(DlssSessionState.DISABLED, session.state)
		assertEquals(DlssFrameRoute.VANILLA, session.beginFrame(true, output).route)
		assertNull(DlssLifecycleAdapter(session, native).initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")))
		assertEquals(0, native.initializeCalls)
	}

	@Test
	fun nativeMustBeReadyBeforeNormalWorldFrameUsesDlss() {
		val session = DlssSession(config())

		assertEquals(DlssSessionState.WAITING_FOR_VULKAN, session.state)
		assertEquals(DlssFrameRoute.VANILLA, session.beginFrame(true, output).route)
		assertTrue(
			DlssLifecycleAdapter(session, FakeNative()).initialize(
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
			DlssLifecycleAdapter(session, FakeNative()).initialize(
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
			session.beginFrame(true, DlssDimensions(1920, 1080)).route,
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
		assertNull(DlssLifecycleAdapter(session, native).initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")))
		assertEquals(0, native.initializeCalls)
	}

	@Test
	fun initializeFailureLatchesVanillaAndPreventsRetry() {
		val diagnostics = mutableListOf<String>()
		val native = FakeNative(initializeResult = 17)
		val session = DlssSession(config(), diagnostics::add)
		val adapter = DlssLifecycleAdapter(session, native)

		assertNull(adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")))
		assertEquals(DlssSessionState.FALLBACK_LATCHED, session.state)
		assertEquals(DlssNativeFailure(DlssNativeStage.INITIALIZE, 17), session.failure)
		assertEquals("DLSS fallback latched: stage=initialize result=0x00000011", diagnostics.single())
		assertEquals(DlssFrameRoute.VANILLA, session.beginFrame(true, output).route)

		assertNull(adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")))
		assertEquals(1, native.initializeCalls)
	}

	@Test
	fun queryFailureLatchesExactStageAndPreventsConfigureRetry() {
		val native = FakeNative(queryResult = 23)
		val session = DlssSession(config())
		val adapter = DlssLifecycleAdapter(session, native)

		assertNull(adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")))
		assertEquals(
			DlssNativeFailure(DlssNativeStage.QUERY_DIMENSIONS, 23),
			session.failure,
		)
		assertEquals(DlssSessionState.FALLBACK_LATCHED, session.state)
		assertEquals(0, native.configureCalls)
		assertNull(adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")))
		assertEquals(1, native.initializeCalls)
		assertEquals(1, native.queryCalls)
	}

	@Test
	fun configureFailureLatchesExactStageAndPreventsReadyState() {
		val native = FakeNative(configureResult = 31)
		val session = DlssSession(config())
		val adapter = DlssLifecycleAdapter(session, native)

		assertNull(adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")))
		assertEquals(
			DlssNativeFailure(DlssNativeStage.CONFIGURE, 31),
			session.failure,
		)
		assertEquals(DlssSessionState.FALLBACK_LATCHED, session.state)
		assertEquals(DlssFrameRoute.VANILLA, session.beginFrame(true, output).route)
		assertNull(adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")))
		assertEquals(1, native.configureCalls)
	}

	@Test
	fun evaluateFailureLatchesVanillaAndPreventsRetry() {
		val native = FakeNative(evaluateResult = 41)
		val session = DlssSession(config())
		val adapter = DlssLifecycleAdapter(session, native)
		assertTrue(adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")) != null)
		assertEquals(DlssFrameRoute.DLSS, session.beginFrame(true, output).route)

		assertFalse(adapter.evaluate(DlssEvaluationRequest()))
		assertEquals(
			DlssNativeFailure(DlssNativeStage.EVALUATE, 41),
			session.failure,
		)
		assertEquals(DlssFrameRoute.VANILLA, session.beginFrame(true, output).route)

		assertFalse(adapter.evaluate(DlssEvaluationRequest()))
		assertEquals(1, native.evaluateCalls)
	}

	@Test
	fun evaluateForwardsCompleteResourceMetadata() {
		val native = FakeNative()
		val session = DlssSession(config())
		val adapter = DlssLifecycleAdapter(session, native)
		assertTrue(adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")) != null)
		val request = DlssEvaluationRequest(
			commandBuffer = 10L,
			colorView = 11L,
			colorImage = 12L,
			colorFormat = 13,
			colorAspectMask = 14,
			colorBaseMipLevel = 15,
			colorLevelCount = 16,
			colorBaseArrayLayer = 17,
			colorLayerCount = 18,
			depthView = 21L,
			depthImage = 22L,
			depthFormat = 23,
			depthAspectMask = 24,
			depthBaseMipLevel = 25,
			depthLevelCount = 26,
			depthBaseArrayLayer = 27,
			depthLayerCount = 28,
			motionView = 31L,
			motionImage = 32L,
			motionFormat = 33,
			motionAspectMask = 34,
			motionBaseMipLevel = 35,
			motionLevelCount = 36,
			motionBaseArrayLayer = 37,
			motionLayerCount = 38,
			outputView = 41L,
			outputImage = 42L,
			outputFormat = 43,
			outputAspectMask = 44,
			outputBaseMipLevel = 45,
			outputLevelCount = 46,
			outputBaseArrayLayer = 47,
			outputLayerCount = 48,
		)

		assertTrue(adapter.evaluate(request))
		assertEquals(request, native.lastEvaluation)
	}

	private class FakeNative(
		private val initializeResult: Int = 1,
		private val queryResult: Int? = null,
		private val configureResult: Int = 1,
		private val evaluateResult: Int = 1,
		private val acquireImagesResult: Int? = null,
		private val releaseImagesResult: Int = 1,
	) : DlssNativeApi {
		var initializeCalls = 0
		var queryCalls = 0
		var configureCalls = 0
		var evaluateCalls = 0
		var acquireImageCalls = 0
		var releaseImageCalls = 0
		var lastEvaluation: DlssEvaluationRequest? = null
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
		): DlssDimensions {
			queryCalls++
			queryResult?.let { throw DlssNativeException("query-dimensions", it) }
			return DlssDimensions(1280, 720)
		}

		override fun configure(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
		): Int {
			configureCalls++
			return configureResult
		}

		override fun acquireImages(): DlssEvaluationImages {
			acquireImageCalls++
			acquireImagesResult?.let { throw DlssNativeException("acquire-images", it) }
			return DlssEvaluationImages(
				motionImage = 0x1001,
				motionView = 0x1002,
				motionFormat = 83,
				outputImage = 0x2001,
				outputView = 0x2002,
				outputFormat = 37,
			)
		}

		override fun releaseImages(): Int {
			releaseImageCalls++
			return releaseImagesResult
		}

		override fun evaluate(
			commandBuffer: Long,
			colorView: Long,
			colorImage: Long,
			colorFormat: Int,
			colorAspectMask: Int,
			colorBaseMipLevel: Int,
			colorLevelCount: Int,
			colorBaseArrayLayer: Int,
			colorLayerCount: Int,
			depthView: Long,
			depthImage: Long,
			depthFormat: Int,
			depthAspectMask: Int,
			depthBaseMipLevel: Int,
			depthLevelCount: Int,
			depthBaseArrayLayer: Int,
			depthLayerCount: Int,
			motionView: Long,
			motionImage: Long,
			motionFormat: Int,
			motionAspectMask: Int,
			motionBaseMipLevel: Int,
			motionLevelCount: Int,
			motionBaseArrayLayer: Int,
			motionLayerCount: Int,
			outputView: Long,
			outputImage: Long,
			outputFormat: Int,
			outputAspectMask: Int,
			outputBaseMipLevel: Int,
			outputLevelCount: Int,
			outputBaseArrayLayer: Int,
			outputLayerCount: Int,
			renderWidth: Int,
			renderHeight: Int,
			outputWidth: Int,
			outputHeight: Int,
			jitterX: Float,
			jitterY: Float,
			motionScaleX: Float,
			motionScaleY: Float,
			frameTimeMilliseconds: Float,
			resetHistory: Boolean,
		): Int {
			evaluateCalls++
			lastEvaluation = DlssEvaluationRequest(
				commandBuffer = commandBuffer,
				colorView = colorView,
				colorImage = colorImage,
				colorFormat = colorFormat,
				colorAspectMask = colorAspectMask,
				colorBaseMipLevel = colorBaseMipLevel,
				colorLevelCount = colorLevelCount,
				colorBaseArrayLayer = colorBaseArrayLayer,
				colorLayerCount = colorLayerCount,
				depthView = depthView,
				depthImage = depthImage,
				depthFormat = depthFormat,
				depthAspectMask = depthAspectMask,
				depthBaseMipLevel = depthBaseMipLevel,
				depthLevelCount = depthLevelCount,
				depthBaseArrayLayer = depthBaseArrayLayer,
				depthLayerCount = depthLayerCount,
				motionView = motionView,
				motionImage = motionImage,
				motionFormat = motionFormat,
				motionAspectMask = motionAspectMask,
				motionBaseMipLevel = motionBaseMipLevel,
				motionLevelCount = motionLevelCount,
				motionBaseArrayLayer = motionBaseArrayLayer,
				motionLayerCount = motionLayerCount,
				outputView = outputView,
				outputImage = outputImage,
				outputFormat = outputFormat,
				outputAspectMask = outputAspectMask,
				outputBaseMipLevel = outputBaseMipLevel,
				outputLevelCount = outputLevelCount,
				outputBaseArrayLayer = outputBaseArrayLayer,
				outputLayerCount = outputLayerCount,
			)
			return evaluateResult
		}
	}

	private fun config(enabled: Boolean = true): DlssStartupConfig = DlssStartupConfig(
		enabled = enabled,
		qualityMode = DlssQualityMode.QUALITY,
		outputDimensions = output,
		sdkPath = null,
		nativeLibraryPath = null,
		dataPath = null,
		warnings = emptyList(),
	)
}
