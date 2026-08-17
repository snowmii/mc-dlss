package me.snowmii.dlss.fg

import java.nio.file.Path
import me.snowmii.streamline.NativeApiTestDouble
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.EvaluationImages
import me.snowmii.streamline.FrameTimings
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.streamline.ImageBinding
import me.snowmii.streamline.MotionRequest
import me.snowmii.streamline.NativeApi
import me.snowmii.streamline.PresentTarget
import me.snowmii.dlss.render.FrameEvaluation
import me.snowmii.dlss.render.WorldPhase
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssSessionState
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.session.SRMode
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
/** Reflex marker adapter and mod-owned world-phase wiring. */
class ReflexMarkersTest {

	@Test
	fun `the adapter gates the marker surface on READY and never latches a failed marker call`() {
		val calls = RecordingNativeApi()
		val session = session()
		val adapter = LifecycleAdapter(session, calls)
		// Not READY yet: every marker is refused before it reaches the native side.
		assertFalse(adapter.reflexInputSample(), "a non-READY session must refuse the input sample")
		assertFalse(adapter.reflexMarker(NativeApi.ReflexMarkerType.SIMULATION_START), "a non-READY session must refuse the simulation start")
		assertFalse(adapter.reflexMarker(NativeApi.ReflexMarkerType.SIMULATION_END), "a non-READY session must refuse the simulation end")
		assertFalse(adapter.reflexMarker(NativeApi.ReflexMarkerType.RENDER_SUBMIT_START), "a non-READY session must refuse the render-submit start")
		assertFalse(adapter.reflexMarker(NativeApi.ReflexMarkerType.RENDER_SUBMIT_END), "a non-READY session must refuse the render-submit end")
		assertTrue(calls.reflexCalls.isEmpty(), "refused markers must never reach the native side")

		// READY: every marker delegates and reports the native result.
		adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data"))
		assertEquals(DlssSessionState.READY, session.state)
		assertTrue(adapter.reflexInputSample(), "a READY session must emit the input sample")
		assertTrue(adapter.reflexMarker(NativeApi.ReflexMarkerType.SIMULATION_START), "a READY session must emit the simulation start")
		assertTrue(adapter.reflexMarker(NativeApi.ReflexMarkerType.SIMULATION_END), "a READY session must emit the simulation end")
		assertTrue(adapter.reflexMarker(NativeApi.ReflexMarkerType.RENDER_SUBMIT_START), "a READY session must emit the render-submit start")
		assertTrue(adapter.reflexMarker(NativeApi.ReflexMarkerType.RENDER_SUBMIT_END), "a READY session must emit the render-submit end")
		assertEquals(
			listOf("inputSample", "simulateStart", "simulateEnd", "renderSubmitStart", "renderSubmitEnd"),
			calls.reflexCalls,
			"the five marker entries must reach the native side in call order",
		)

		// A failed native marker call reports false without latching: the markers are the
		// PCL/Reflex diagnostic surface, not a frame-route stage, and a session that
		// rendered the frame anyway must keep rendering rather than degrade because a ping
		// did not reach the plugin.
		calls.failReflex = true
		assertFalse(adapter.reflexInputSample(), "a failed native marker call must report false")
		assertEquals(
			DlssSessionState.READY,
			session.state,
			"a failed marker call must never latch the session",
		)
		assertNull(session.failure, "a failed marker call must record no latched failure")
	}

	@Test
	fun `the world phase delegates the marker surface through the evaluation to the adapter in production order`() {
		val calls = RecordingNativeApi()
		val session = session()
		val adapter = LifecycleAdapter(session, calls)
		adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data"))
		val evaluation = FrameEvaluation(adapter, { null })

		// The world phase is the seam the Minecraft mixins call; a runtime carrying the
		// production evaluation is the wiring that connects it to the adapter.
		val phase = WorldPhase(
			runtime = me.snowmii.dlss.render.RenderRuntime(
				session = session,
				sceneTarget = me.snowmii.dlss.render.SceneTarget(
					allocate = { width, height -> HeadlessRenderTarget(width, height) },
					release = {},
				),
				startup = { render },
				frameEvaluation = evaluation,
			),
			present = { _, _ -> },
			onWorldTargetChanged = {},
		)

		assertTrue(phase.reflexInputSample(), "the world phase must delegate the input sample")
		assertTrue(phase.reflexMarker(NativeApi.ReflexMarkerType.SIMULATION_START), "the world phase must delegate the simulation start")
		assertTrue(phase.reflexMarker(NativeApi.ReflexMarkerType.SIMULATION_END), "the world phase must delegate the simulation end")
		assertTrue(phase.reflexMarker(NativeApi.ReflexMarkerType.RENDER_SUBMIT_START), "the world phase must delegate the render-submit start")
		assertTrue(phase.reflexMarker(NativeApi.ReflexMarkerType.RENDER_SUBMIT_END), "the world phase must delegate the render-submit end")
		assertEquals(
			listOf("inputSample", "simulateStart", "simulateEnd", "renderSubmitStart", "renderSubmitEnd"),
			calls.reflexCalls,
			"the five markers must reach the native side through the world phase in production order",
		)
	}

	private val output = Dimensions(2560, 1440)
	private val render = Dimensions(1707, 960)

	private fun session() = DlssSession(
		DlssStartupConfig(
			enabled = true,
			qualityMode = SRMode.QUALITY,
			outputDimensions = output,
			sdkPath = null,
			nativeLibraryPath = null,
			dataPath = null,
			warnings = emptyList(),
		),
	)

	/**
	 * Records every reflex marker call in order so the delegation chain is assertable off the
	 * render thread; everything else the lifecycle needs answers success.
	 */
	private class RecordingNativeApi : NativeApiTestDouble() {
		val reflexCalls = mutableListOf<String>()
		var failReflex = false

		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		): Int = NativeApi.SUCCESS_RESULT

		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int): Dimensions =
			Dimensions(1707, 960)

		override fun configureSuperResolution(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		): Int = NativeApi.SUCCESS_RESULT

		override fun acquireImages(): EvaluationImages = EvaluationImages(
			ImageBinding(401L, 402L, 124),
			ImageBinding(501L, 502L, 37),
		)

		override fun releaseImages(): Int = NativeApi.SUCCESS_RESULT

		override fun waitDeviceIdle(): Int = NativeApi.SUCCESS_RESULT

		override fun frameTimings(): FrameTimings? = null

		override fun writeMotion(request: MotionRequest): Int = NativeApi.SUCCESS_RESULT

		override fun presentOutput(target: PresentTarget): Int = NativeApi.SUCCESS_RESULT

		override fun evaluateSuperResolution(request: EvaluationRequest): Int = NativeApi.SUCCESS_RESULT

		override fun reflexInputSample(): Int {
			reflexCalls += "inputSample"
			return if (failReflex) NativeApi.SUCCESS_RESULT + 1 else NativeApi.SUCCESS_RESULT
		}

		override fun reflexMarker(type: NativeApi.ReflexMarkerType): Int {
			reflexCalls += when (type) {
				NativeApi.ReflexMarkerType.SIMULATION_START -> "simulateStart"
				NativeApi.ReflexMarkerType.SIMULATION_END -> "simulateEnd"
				NativeApi.ReflexMarkerType.RENDER_SUBMIT_START -> "renderSubmitStart"
				NativeApi.ReflexMarkerType.RENDER_SUBMIT_END -> "renderSubmitEnd"
				NativeApi.ReflexMarkerType.INPUT_SAMPLE -> return FAIL_INVALID_PARAMETER
			}
			return NativeApi.SUCCESS_RESULT
		}
	}

	private class HeadlessRenderTarget(width: Int, height: Int) : RenderTarget("fake", true, GpuFormat.RGBA8_UNORM) {
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

	private companion object {
		/** NVSDK_NGX_Result_FAIL_InvalidParameter = NVSDK_NGX_Result_Fail | 5 (0xBAD00000 | 5). */
		const val FAIL_INVALID_PARAMETER = 0xBAD00005.toInt()
	}
}
