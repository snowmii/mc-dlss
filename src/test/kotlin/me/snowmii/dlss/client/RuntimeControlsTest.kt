package me.snowmii.dlss.client
import me.snowmii.dlss.bridge.ImageBinding
import me.snowmii.dlss.bridge.EvaluationRequest
import me.snowmii.dlss.bridge.PresentTarget
import me.snowmii.dlss.bridge.MotionRequest
import me.snowmii.dlss.bridge.NativeApi
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.SRMode
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.session.DlssSessionState
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.render.RenderRuntime
import me.snowmii.dlss.render.WorldPhase
import me.snowmii.dlss.render.SceneTarget
import me.snowmii.dlss.bridge.DlssEvaluationImages
import me.snowmii.dlss.bridge.DlssFrameTimings
import me.snowmii.dlss.render.DlssCameraSample
import me.snowmii.dlss.render.DlssFrameMotion
import me.snowmii.dlss.readout.SessionReadout

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * M-14's rung: a running session changes what DLSS is doing, without restarting.
 *
 * Everything beneath this milestone made DLSS work at whatever the JVM was started with. That is
 * enough to run and not enough to review: AC-2 asks a human to watch the internal resolution change
 * while the window does not, and AC-5 asks them to watch full-resolution rendering come back - both
 * comparisons, and until now both meant quitting and starting a second client with a different
 * property, which compares two sessions rather than one switch.
 *
 * So the rung drives the real production stack - controls, runtime, lifecycle adapter, session, and
 * world phase - off the render thread, with the native side as a double. What it holds is that a
 * change reaches every piece of state sized from the configuration, that a refused change leaves
 * the session exactly as it was, and that what the reviewer is told is what is actually running.
 */
class RuntimeControlsTest {
	private val output = DlssDimensions(2560, 1440)
	private val mainTarget = FakeTarget(output.width, output.height)

	@Test
	fun `switching DLSS off returns the world to full resolution and back on resumes it`() {
		val fixture = fixture()

		assertEquals(1280, fixture.frame().width, "the session starts on its configured mode")

		fixture.controls.toggleEnabled()
		assertSame(mainTarget, fixture.frame(), "a switched-off session renders Minecraft's own target")
		assertSame(mainTarget, fixture.frame(), "and keeps rendering it")
		assertTrue(fixture.released > 0, "the low-resolution target must not stay allocated")

		fixture.controls.toggleEnabled()
		assertEquals(1280, fixture.frame().width, "switching back on resumes the DLSS route")
	}

	@Test
	fun `every control that frees a GPU resource waits for the frames still reading it`() {
		val fixture = fixture()

		fixture.frame()
		fixture.events.clear()

		// The three controls, each of which releases the scene target the frames just drawn into.
		fixture.controls.cycleQualityMode()
		fixture.frame()
		fixture.controls.toggleEnabled()
		fixture.frame()

		assertTrue(fixture.events.contains("release-target"), "the target must actually be released")
		fixture.events.forEachIndexed { index, event ->
			if (event == "release-target") {
				assertEquals(
					"wait-device-idle",
					fixture.events.getOrNull(index - 1),
					"a release that is not preceded by a device wait frees an image the queued " +
						"frames still read, which loses the device: ${fixture.events}",
				)
			}
		}
	}

	@Test
	fun `a session switched off before its first frame never initializes NGX`() {
		val fixture = fixture()

		fixture.controls.toggleEnabled()
		repeat(3) { fixture.frame() }

		assertEquals(0, fixture.native.initializeCalls)
		assertEquals(DlssSessionState.WAITING_FOR_VULKAN, fixture.session.state)
	}

	@Test
	fun `cycling the quality mode changes the internal resolution and leaves the output alone`() {
		val fixture = fixture()

		val before = fixture.frame()
		assertEquals(1280, before.width)

		fixture.controls.cycleQualityMode()
		val after = fixture.frame()

		assertNotEquals(before.width, after.width, "a mode change must change the internal resolution")
		assertEquals(fixture.native.renderFor(fixture.runtime.qualityMode).width, after.width)
		assertEquals(output, fixture.runtime.config.outputDimensions, "the output size is fixed")
		assertEquals(output.width, mainTarget.width, "Minecraft's own target is never resized")
	}

	@Test
	fun `a mode change rebuilds every piece of state sized from the render dimensions`() {
		val fixture = fixture()

		fixture.frame()
		fixture.frame()
		assertFalse(fixture.motion!!.reset, "two continuous frames accumulate")

		fixture.controls.cycleQualityMode()
		fixture.frame()

		assertEquals(fixture.runtime.renderDimensions, fixture.native.lastConfiguredRender)
		assertTrue(fixture.motion!!.reset, "history cannot carry across a resolution change")
		assertTrue(fixture.released > 0, "the scene target is released rather than reused at the old size")
	}

	@Test
	fun `cycling the preset re-configures without changing the internal resolution`() {
		val fixture = fixture()

		val before = fixture.frame()
		val presetBefore = fixture.runtime.renderPreset

		fixture.controls.cyclePreset()
		val after = fixture.frame()

		assertNotEquals(presetBefore, fixture.runtime.renderPreset)
		assertEquals(before.width, after.width, "a preset change is not a resolution change")
		assertEquals(fixture.runtime.renderPreset.ngxValue, fixture.native.lastConfiguredPreset)
	}

	@Test
	fun `a preset chosen deliberately survives a mode change and a default does not`() {
		val fixture = fixture()
		fixture.frame()

		// The configured session runs its mode's default, so cycling the mode takes the next
		// mode's default with it.
		fixture.controls.cycleQualityMode()
		assertEquals(fixture.runtime.qualityMode.defaultPreset, fixture.runtime.renderPreset)

		// Once the reviewer has chosen one, it is theirs and stays across the next mode.
		fixture.controls.cyclePreset()
		val chosen = fixture.runtime.renderPreset
		fixture.controls.cycleQualityMode()
		assertEquals(chosen, fixture.runtime.renderPreset)
	}

	@Test
	fun `a refused reconfiguration leaves the session on what it was already running`() {
		val fixture = fixture()

		fixture.frame()
		val mode = fixture.runtime.qualityMode
		val preset = fixture.runtime.renderPreset
		val dimensions = fixture.runtime.renderDimensions

		fixture.native.configureResult = 0xBAD00005.toInt()
		fixture.controls.cycleQualityMode()

		assertEquals(mode, fixture.runtime.qualityMode)
		assertEquals(preset, fixture.runtime.renderPreset)
		assertEquals(dimensions, fixture.runtime.renderDimensions)
		assertTrue(
			fixture.announced.last().startsWith("DLSS kept ${mode.propertyValue}/${preset.propertyValue};"),
			"the reviewer must be told the frames did not change: ${fixture.announced.last()}",
		)
	}

	@Test
	fun `every readout names the mode, preset, internal resolution, and output resolution`() {
		val fixture = fixture()
		fixture.frame()

		fixture.controls.cycleQualityMode()
		val readout = fixture.announced.last()

		assertTrue(readout.contains("mode ${fixture.runtime.qualityMode.propertyValue}"), readout)
		assertTrue(readout.contains("preset ${fixture.runtime.renderPreset.propertyValue}"), readout)
		assertTrue(readout.contains("internal ${fixture.runtime.renderDimensions}"), readout)
		assertTrue(readout.contains("output $output"), readout)

		fixture.controls.toggleEnabled()
		assertTrue(fixture.announced.last().startsWith("DLSS off"), fixture.announced.last())
	}

	private fun fixture() = Fixture()

	/** The production stack, wired the way `RenderRuntime.forMinecraft` wires it. */
	private inner class Fixture {
		val diagnostics = mutableListOf<String>()
		val announced = mutableListOf<String>()
		val events = mutableListOf<String>()
		val native = FakeNative().apply { log = events::add }
		var released = 0
		var motion: DlssFrameMotion? = null
		private var now = 0L

		val session = DlssSession(
			DlssStartupConfig(
				enabled = true,
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
				allocate = { width, height -> FakeTarget(width, height) },
				release = {
					released++
					events.add("release-target")
				},
			),
			startup = { adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")) },
			clock = {
				now += 16_000_000L
				now
			},
			reconfigure = adapter::reconfigure,
			quiesce = { adapter.waitDeviceIdle() },
		)

		val controls = RuntimeControls(runtime, announced::add)

		private val phase = WorldPhase(
			runtime = runtime,
			present = { _, _ -> },
			onWorldTargetChanged = {},
			readout = SessionReadout(diagnostics::add),
		)

		/** One world frame through both seams: the projection upload, then the phase itself. */
		fun frame(target: RenderTarget = mainTarget): RenderTarget {
			phase.prepare(true, target, camera())
			val resolved = phase.begin(true, target)
			motion = runtime.activeMotion
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

	/** Render target with no GPU buffers, so the whole stack is testable off the render thread. */
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

	/**
	 * The native bridge as dimensions and results.
	 *
	 * The render size answers per mode the way NGX does, because a mode change that returned the
	 * same size would let a runtime that rebuilt nothing pass.
	 */
	private class FakeNative : NativeApi {
		var initializeCalls = 0
		var releaseImageCalls = 0
		var waitDeviceIdleCalls = 0

		/** Records the calls whose *order* is the invariant, not just their count. */
		var log: ((String) -> Unit)? = null
		var configureResult = NativeApi.SUCCESS_RESULT
		var lastConfiguredRender: DlssDimensions? = null
		var lastConfiguredPreset: Int? = null

		fun renderFor(mode: SRMode): DlssDimensions = when (mode) {
			SRMode.DLAA -> DlssDimensions(2560, 1440)
			SRMode.QUALITY -> DlssDimensions(1280, 720)
			SRMode.BALANCED -> DlssDimensions(1485, 835)
			SRMode.PERFORMANCE -> DlssDimensions(1706, 960)
			SRMode.ULTRA_PERFORMANCE -> DlssDimensions(853, 480)
		}

		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		): Int {
			initializeCalls++
			return NativeApi.SUCCESS_RESULT
		}

		override fun queryOptimalDimensions(
			outputWidth: Int,
			outputHeight: Int,
			qualityMode: Int,
		): DlssDimensions = renderFor(SRMode.entries.first { it.ngxValue == qualityMode })

		override fun configure(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		): Int {
			if (configureResult != NativeApi.SUCCESS_RESULT) {
				return configureResult
			}
			lastConfiguredRender = DlssDimensions(renderWidth, renderHeight)
			lastConfiguredPreset = renderPreset
			return NativeApi.SUCCESS_RESULT
		}

		override fun acquireImages() = DlssEvaluationImages(
			motion = ImageBinding(0x1002, 0x1001, 83),
			output = ImageBinding(0x2002, 0x2001, 37),
		)

		override fun releaseImages(): Int {
			releaseImageCalls++
			log?.invoke("release-images")
			return NativeApi.SUCCESS_RESULT
		}

		override fun frameTimings(): DlssFrameTimings? = null

		override fun waitDeviceIdle(): Int {
			waitDeviceIdleCalls++
			log?.invoke("wait-device-idle")
			return NativeApi.SUCCESS_RESULT
		}

		override fun writeMotion(request: MotionRequest): Int = NativeApi.SUCCESS_RESULT

		override fun presentOutput(target: PresentTarget): Int = NativeApi.SUCCESS_RESULT

		override fun evaluate(request: EvaluationRequest): Int = NativeApi.SUCCESS_RESULT
	}
}
