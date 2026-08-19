package me.snowmii.dlss.fg

import me.snowmii.dlss.render.DlssCameraSample
import me.snowmii.dlss.render.RenderRuntime
import me.snowmii.dlss.render.SceneTarget
import me.snowmii.dlss.render.WorldPhase
import me.snowmii.dlss.render.WorldTargetRoute
import me.snowmii.dlss.readout.SessionReadout
import me.snowmii.dlss.session.DlssFrameRoute
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.session.SRMode
import me.snowmii.dlss.session.TestSessionBridge
import me.snowmii.streamline.StreamlineSessionTestDouble
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.EvaluationImages
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.streamline.FgState
import me.snowmii.streamline.FrameTimings
import me.snowmii.streamline.ImageBinding
import me.snowmii.streamline.MotionRequest
import me.snowmii.streamline.StreamlineSession
import me.snowmii.streamline.PresentTarget
import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Output size follows the client's main render target: the first world frame adopts the size
 * the client is rendering into; a later change reconfigures; a drag-resize does not reconfigure
 * per intermediate size; FG suspends across the change and resumes; an explicitly configured
 * size still pins.
 *
 * A maximized windowed client is typically a few pixels short of the monitor, so a startup pin
 * would route those frames vanilla.
 */
class OutputResolutionTest {
	private val configured = Dimensions(2560, 1440)
	private val windowed = Dimensions(1920, 1080)

	@Test
	fun `the first world frame adopts the client's own main-target size`() {
		val fixture = fixture()

		val resolved = fixture.frame(HeadlessRenderTarget(windowed))

		assertEquals(DlssFrameRoute.DLSS, fixture.route?.frame?.route, "the client's size must not route vanilla")
		assertEquals(windowed, fixture.runtime.outputDimensions, "the session must run at the client's size")
		assertEquals(Dimensions(960, 540), Dimensions(resolved.width, resolved.height))
		assertEquals(windowed, fixture.native.configuredOutput, "the native side must be configured at that size")
	}

	@Test
	fun `an explicitly configured size pins the session and refuses every other size`() {
		val fixture = fixture(outputPinned = true)

		val target = HeadlessRenderTarget(windowed)
		val resolved = fixture.frame(target)

		assertSame(target, resolved, "a pinned session renders full-resolution at the client's size")
		assertEquals("unsupported-output-size", fixture.route?.frame?.reason)
		assertEquals(configured, fixture.runtime.outputDimensions, "the pin must hold")
		assertEquals(configured, fixture.native.configuredOutput, "and nothing may reconfigure it")
	}

	@Test
	fun `a live resize reconfigures on the first stable frame at the new size`() {
		val fixture = fixture()
		fixture.frame(HeadlessRenderTarget(windowed))
		val configuresBefore = fixture.native.configureCalls

		val duringChange = fixture.frame(HeadlessRenderTarget(configured))
		assertEquals(
			"unsupported-output-size",
			fixture.route?.frame?.reason,
			"the frame that first reports a new size renders at that size, vanilla",
		)
		assertEquals(configured.width, duringChange.width)
		assertEquals(configuresBefore, fixture.native.configureCalls, "one frame is not a settled size")

		fixture.frame(HeadlessRenderTarget(configured))
		assertEquals(DlssFrameRoute.DLSS, fixture.route?.frame?.route, "the settled size resumes DLSS")
		assertEquals(configured, fixture.runtime.outputDimensions)
		assertEquals(Dimensions(1280, 720), fixture.runtime.dlssRenderDimensions, "the render size follows the new output")
		assertEquals(configuresBefore + 1, fixture.native.configureCalls, "a settled change costs exactly one reconfigure")
	}

	@Test
	fun `a drag-resize spends no reconfigure on the sizes it passes through`() {
		val fixture = fixture()
		fixture.frame(HeadlessRenderTarget(windowed))
		val configuresBefore = fixture.native.configureCalls

		// Every frame of a drag reports a size the one before it did not.
		listOf(1930, 1940, 1950, 1960).forEach { width ->
			fixture.frame(HeadlessRenderTarget(Dimensions(width, 1080)))
			assertEquals("unsupported-output-size", fixture.route?.frame?.reason)
		}

		assertEquals(configuresBefore, fixture.native.configureCalls, "an unsettled drag must not reconfigure")

		fixture.frame(HeadlessRenderTarget(Dimensions(1960, 1080)))
		assertEquals(
			configuresBefore + 1,
			fixture.native.configureCalls,
			"the size the drag came to rest on costs one reconfigure",
		)
		assertEquals(Dimensions(1960, 1080), fixture.runtime.outputDimensions)
	}

	@Test
	fun `frame generation suspends across the change and resumes at the new size`() {
		val fixture = fixture()
		fixture.frame(HeadlessRenderTarget(windowed))
		fixture.runtime.frameGeneration.setFrameGenerationActive(true)
		fixture.frame(HeadlessRenderTarget(windowed))
		assertTrue(fixture.runtime.frameGeneration.effective, "FG composes at the settled size")

		fixture.frame(HeadlessRenderTarget(configured))
		assertFalse(fixture.runtime.frameGeneration.effective, "the frame that reports a new size must not compose FG")
		assertTrue(fixture.runtime.frameGeneration.userEnabled, "and the user's FG mode must survive the suspension")

		fixture.frame(HeadlessRenderTarget(configured))
		fixture.frame(HeadlessRenderTarget(configured))
		assertTrue(fixture.runtime.frameGeneration.effective, "FG resumes once the session runs at the new size")
	}

	@Test
	fun `a refused reconfigure leaves the session running at the size it was already configured for`() {
		// A bridge that answers nothing is the refusal shape: no native size to move to, so the
		// session must not claim one.
		val fixture = fixture(bridge = TestSessionBridge())

		fixture.frame(HeadlessRenderTarget(windowed))
		fixture.frame(HeadlessRenderTarget(windowed))

		assertEquals(configured, fixture.runtime.outputDimensions, "a refused reconfigure changes nothing")
		assertEquals("unsupported-output-size", fixture.route?.frame?.reason)
	}

	private fun fixture(
		outputPinned: Boolean = false,
		bridge: me.snowmii.dlss.session.SessionBridge? = null,
	) = Fixture(outputPinned, bridge)

	private inner class Fixture(outputPinned: Boolean, overrideBridge: me.snowmii.dlss.session.SessionBridge?) {
		val diagnostics = mutableListOf<String>()
		val native = FakeNative()
		var route: WorldTargetRoute? = null

		val session = DlssSession(
			DlssStartupConfig(
				enabled = true,
				qualityMode = SRMode.QUALITY,
				outputDimensions = configured,
				sdkPath = null,
				nativeLibraryPath = null,
				dataPath = null,
				warnings = emptyList(),
				outputPinned = outputPinned,
			),
			diagnostics::add,
		)

		private val adapter = LifecycleAdapter(session, native)

		val runtime = RenderRuntime(
			session = session,
			sceneTarget = SceneTarget(
				allocate = { width, height -> HeadlessRenderTarget(Dimensions(width, height)) },
				release = {},
			),
			startup = { adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")) },
			clock = {
				now += 16_000_000L
				now
			},
			bridge = overrideBridge ?: adapter,
			diagnostics = diagnostics::add,
		)

		private val phase = WorldPhase(
			runtime = runtime,
			present = { _, _ -> },
			onWorldTargetChanged = {},
			readout = SessionReadout(diagnostics::add),
		)

		private var now = 0L

		fun frame(target: RenderTarget): RenderTarget {
			phase.prepare(true, target, camera())
			val resolved = phase.begin(true, target)
			route = runtime.worldTargetRoute
			phase.end()
			return resolved
		}

		private fun camera() = DlssCameraSample(
			projection = Matrix4f().perspective(1.2f, 16f / 9f, 0.05f, 1000f),
			viewRotation = Matrix4f(),
			cameraX = 0.0,
			cameraY = 64.0,
			cameraZ = 0.0,
		)
	}

	private class HeadlessRenderTarget(dimensions: Dimensions) : RenderTarget("fake", true, GpuFormat.RGBA8_UNORM) {
		init {
			this.width = dimensions.width
			this.height = dimensions.height
		}

		override fun createBuffers(width: Int, height: Int) {
			this.width = width
			this.height = height
		}

		override fun destroyBuffers() = Unit
	}

	/**
	 * The native side as sizes: half the output in each axis, which is no real quality mode's
	 * ratio and is exactly why it is used - a render size that moved can only have come from the
	 * output size the reconfigure was given.
	 */
	private class FakeNative : StreamlineSessionTestDouble() {
		var configureCalls = 0
		var configuredOutput: Dimensions? = null

		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		) = StreamlineSession.SUCCESS_RESULT

		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int) =
			Dimensions(outputWidth / 2, outputHeight / 2)

		override fun configureSuperResolution(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		): Int {
			configureCalls++
			configuredOutput = Dimensions(outputWidth, outputHeight)
			return StreamlineSession.SUCCESS_RESULT
		}

		override fun acquireImages() = EvaluationImages(
			ImageBinding(0x1002, 0x1001, 83),
			ImageBinding(0x2002, 0x2001, 37),
		)

		override fun releaseImages() = StreamlineSession.SUCCESS_RESULT

		override fun waitDeviceIdle() = StreamlineSession.SUCCESS_RESULT

		override fun frameTimings(): FrameTimings? = null

		override fun writeMotion(request: MotionRequest) = StreamlineSession.SUCCESS_RESULT

		override fun presentOutput(target: PresentTarget) = StreamlineSession.SUCCESS_RESULT

		override fun evaluateSuperResolution(request: EvaluationRequest) = StreamlineSession.SUCCESS_RESULT

		/** Healthy status, so the FG status latch never suspends composition for its own reason. */
		override fun queryFgState() = FgState(0, 2, 0L, 0L)
	}
}
