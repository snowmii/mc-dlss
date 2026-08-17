package me.snowmii.dlss.render
import me.snowmii.streamline.NativeApiTestDouble
import me.snowmii.streamline.ImageBinding
import me.snowmii.streamline.PresentTarget
import me.snowmii.streamline.MotionRequest
import me.snowmii.streamline.EvaluationImages
import me.snowmii.streamline.FrameTimings
import me.snowmii.streamline.NativeApi
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.SRMode
import me.snowmii.streamline.Dimensions
import me.snowmii.dlss.session.DlssSessionState
import me.snowmii.dlss.session.DlssFrameRoute
import me.snowmii.dlss.session.DlssNativeStage
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.readout.SessionReadout

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Verifies frame eligibility, history resets, and safe native fallback. Supported in-world frames
 * use DLSS; discontinuities reset history; a failed native stage restores full-resolution rendering
 * and reports the exact stage and result.
 *
 * Everything below drives the production seams - the world phase, the runtime, the lifecycle
 * adapter, and the session - off the render thread. The native side is a double, and the failure
 * path goes through the adapter, which takes its command buffer and images as plain handles, so
 * nothing here needs a GPU.
 */
class EnablementFallbackTest {
	private val output = Dimensions(2560, 1440)
	private val render = Dimensions(1280, 720)

	private val mainTarget = HeadlessRenderTarget(output.width, output.height)

	@Test
	fun `a normal in-world frame renders into the low-resolution scene target`() {
		val fixture = fixture()

		val resolved = fixture.frame(normalInWorldFrame = true)

		assertEquals(render.width, resolved.width)
		assertEquals(render.height, resolved.height)
		assertEquals(DlssFrameRoute.DLSS, fixture.route?.frame?.route)
	}

	@Test
	fun `a panoramic frame renders full-resolution into Minecraft's own target`() {
		val fixture = fixture()

		fixture.frame(normalInWorldFrame = true)
		val resolved = fixture.frame(normalInWorldFrame = false)

		assertSame(mainTarget, resolved)
		assertEquals("unsupported-frame", fixture.route?.frame?.reason)
	}

	// A size the session is not configured against renders full-resolution *for that frame*. This
	// runtime has no bridge, so there is no native side to reconfigure and the refusal is the
	// whole story; the session's own adoption of the client's size is OutputResolutionTest's.
	@Test
	fun `a window the session is not configured against renders full-resolution`() {
		val fixture = fixture()
		val resized = HeadlessRenderTarget(1920, 1080)

		val resolved = fixture.frame(normalInWorldFrame = true, target = resized)

		assertSame(resized, resolved)
		assertEquals("unsupported-output-size", fixture.route?.frame?.reason)
	}

	@Test
	fun `a disabled configuration renders every frame full-resolution and never calls native`() {
		val fixture = fixture(enabled = false)

		assertSame(mainTarget, fixture.frame(normalInWorldFrame = true))
		assertSame(mainTarget, fixture.frame(normalInWorldFrame = true))

		assertEquals(DlssSessionState.DISABLED, fixture.session.state)
		assertEquals(0, fixture.native.initializeCalls)
		assertNull(fixture.runtime.dlssRenderDimensions)
	}

	@Test
	fun `native startup is attempted once and a failed one is never retried`() {
		val fixture = fixture(initializeResult = 0xBAD00001.toInt())

		repeat(3) { assertSame(mainTarget, fixture.frame(normalInWorldFrame = true)) }

		assertEquals(1, fixture.native.initializeCalls)
		assertEquals(DlssSessionState.FALLBACK_LATCHED, fixture.session.state)
		assertEquals(
			"DLSS fallback latched: stage=initialize result=0xBAD00001",
			fixture.latchDiagnostics().single(),
		)
	}

	@Test
	fun `a failed native stage restores full-resolution routing for the rest of the session`() {
		val fixture = fixture()

		val beforeFailure = fixture.frame(normalInWorldFrame = true)
		assertEquals(render.width, beforeFailure.width)

		fixture.native.evaluateResult = 0xBAD00005.toInt()
		assertFalse(fixture.evaluate(), "a failing native stage must report failure")

		repeat(3) { assertSame(mainTarget, fixture.frame(normalInWorldFrame = true)) }
		assertEquals("latched-fallback", fixture.route?.frame?.reason)
		assertTrue(fixture.released > 0, "the low-resolution target must not stay allocated")
		assertEquals(1, fixture.native.evaluateCalls, "a latched session must not evaluate again")
	}

	@Test
	fun `a latched failure names its exact native stage and result exactly once`() {
		val fixture = fixture()

		fixture.frame(normalInWorldFrame = true)
		fixture.native.evaluateResult = 0xBAD00005.toInt()
		fixture.evaluate()
		fixture.evaluate()

		assertEquals(
			"DLSS fallback latched: stage=evaluate result=0xBAD00005",
			fixture.latchDiagnostics().single(),
		)
		assertEquals(DlssNativeStage.EVALUATE, fixture.session.failure?.stage)
		assertEquals(0xBAD00005.toInt(), fixture.session.failure?.resultCode)
	}

	// The vanilla-frame and level-change resets are MotionJitterTest's and LevelChangeResetTest's;
	// this is the one wiring test that the discontinuity predicate reaches the production stack.
	@Test
	fun `a camera that jumped resets the history`() {
		val fixture = fixture()

		fixture.frame(normalInWorldFrame = true)
		fixture.frame(normalInWorldFrame = true)
		assertFalse(fixture.motion!!.reset)

		fixture.frame(normalInWorldFrame = true, cameraX = 12_000.0)
		assertTrue(fixture.motion!!.reset)
	}

	private fun fixture(
		enabled: Boolean = true,
		initializeResult: Int = NativeApi.SUCCESS_RESULT,
	) = Fixture(enabled, initializeResult)

	/** The production stack, wired the way `RenderRuntime.forMinecraft` wires it. */
	private inner class Fixture(enabled: Boolean, initializeResult: Int) {
		val diagnostics = mutableListOf<String>()
		val native = FakeNative(initializeResult, render)
		var released = 0

		/** This frame's route and motion, captured while the phase was still open. */
		var route: WorldTargetRoute? = null
		var motion: DlssFrameMotion? = null

		val session = DlssSession(
			DlssStartupConfig(
				enabled = enabled,
				qualityMode = SRMode.QUALITY,
				outputDimensions = output,
				sdkPath = null,
				nativeLibraryPath = null,
				dataPath = null,
				warnings = emptyList(),
			),
			diagnostics::add,
		)

		private val adapter = LifecycleAdapter(session, native)

		val runtime = RenderRuntime(
			session = session,
			sceneTarget = SceneTarget(
				allocate = { width, height -> HeadlessRenderTarget(width, height) },
				release = { released++ },
			),
			startup = { adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")) },
			clock = {
				now += 16_000_000L
				now
			},
		)

		val phase = WorldPhase(
			runtime = runtime,
			present = { _, _ -> },
			onWorldTargetChanged = {},
			readout = SessionReadout(diagnostics::add),
		)

		private var now = 0L

		/** One world frame through both seams: the projection upload, then the phase itself. */
		fun frame(
			normalInWorldFrame: Boolean,
			target: RenderTarget = mainTarget,
			cameraX: Double = 0.0,
		): RenderTarget {
			phase.prepare(normalInWorldFrame, target, camera(cameraX))
			val resolved = phase.begin(normalInWorldFrame, target)
			// Read inside the phase: closing it drops the route, the jitter, and the motion, which
			// is exactly the window the renderer itself sees them in.
			route = runtime.worldTargetRoute
			motion = runtime.activeMotion
			phase.end()
			return resolved
		}

		/** The fallback diagnostics alone; the phase reports its first decision on the same sink. */
		fun latchDiagnostics() = diagnostics.filter { it.startsWith("DLSS fallback latched") }

		/** One evaluation through the adapter, which takes every handle as a plain value. */
		fun evaluate(): Boolean = adapter.evaluate(EvaluationRequest.builder()
			.commandBuffer(0xF00DL)
			.build())

		private fun camera(x: Double) = DlssCameraSample(
			projection = Matrix4f().perspective(1.2f, 16f / 9f, 0.05f, 1000f),
			viewRotation = Matrix4f(),
			cameraX = x,
			cameraY = 64.0,
			cameraZ = 0.0,
		)
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

	/** The native bridge as results, which is all the enablement and fallback paths read of it. */
	private class FakeNative(private val initializeResult: Int, private val render: Dimensions) : NativeApiTestDouble() {
		var initializeCalls = 0
		var evaluateCalls = 0
		var evaluateResult = NativeApi.SUCCESS_RESULT

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

		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int) = render

		override fun configureSuperResolution(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		) = NativeApi.SUCCESS_RESULT

		override fun acquireImages() = EvaluationImages(
			ImageBinding(0x1002, 0x1001, 83),
			ImageBinding(0x2002, 0x2001, 37),
		)

		override fun releaseImages() = NativeApi.SUCCESS_RESULT

		override fun waitDeviceIdle() = NativeApi.SUCCESS_RESULT

		override fun frameTimings(): FrameTimings? = null

		override fun writeMotion(request: MotionRequest) = NativeApi.SUCCESS_RESULT

		override fun presentOutput(target: PresentTarget) = NativeApi.SUCCESS_RESULT

		@Suppress("LongParameterList")
		override fun evaluateSuperResolution(request: EvaluationRequest): Int {
			evaluateCalls++
			return evaluateResult
		}
	}
}
