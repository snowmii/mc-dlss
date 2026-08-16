package me.snowmii.dlss.fg
import me.snowmii.dlss.session.TestSessionBridge

import java.nio.file.Path
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.EvaluationImages
import me.snowmii.streamline.FrameTimings
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.streamline.FgMultiplier
import me.snowmii.streamline.FillVelocityRequest
import me.snowmii.streamline.MotionRequest
import me.snowmii.dlss.bridge.NativeApi
import me.snowmii.streamline.PresentTarget
import me.snowmii.streamline.SrTagRequest
import me.snowmii.dlss.client.RuntimeControls
import me.snowmii.dlss.readout.AcceptanceRecord
import me.snowmii.dlss.render.RenderRuntime
import me.snowmii.dlss.render.SceneTarget
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssSessionState
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.LifecycleAdapter
import me.snowmii.dlss.session.SRMode
import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M-14.5 rung: the in-game FG multiplier cycle.
 *
 * F12 cycles the FG multiplier from the 2x default up through the device's
 * `numFramesToGenerateMax` and wraps back to 2x. The cycle is proven through the public
 * boundaries: the controls key action, the runtime's cycle and set seams, the adapter's
 * non-latching native record, and the acceptance record's multiplier field - against a
 * fake bridge that answers the multiplier query and records the native set calls. The mixin
 * that maps F12 is a thin handler by design and is not unit tested; what is proven here is
 * the mod-owned object it delegates to, exactly like the F10 toggle rung.
 *
 * The invariant each test protects:
 *
 * - the cycle walks 2x..ceiling and wraps, offering nothing outside the device's max;
 * - each real change records the corresponding numFramesToGenerate exactly once and
 *   invalidates the surface reconfigure path exactly once;
 * - a refused record (or a session that cannot answer) changes nothing: the multiplier,
 *   the reconfigure count, and the readout all stay on the value in effect;
 * - the readout and the acceptance record report the active multiplier, not a fixed 2x.
 */
class FgMultiplierToggleTest {

	@Test
	fun `the cycle walks 2x through the device ceiling and wraps with one reconfigure per real change`() {
		val calls = MultiplierNative(max = 3)
		var invalidations = 0
		val harness = harness(calls, invalidateSurfaceConfiguration = { invalidations++ })
		val controls = RuntimeControls(harness.runtime, announced::add)

		controls.toggleFrameGeneration()
		assertTrue(announced.last().contains("fg on at 2x"), "the armed readout names the 2x default: ${announced.last()}")

		// 2x -> 3x: one native record carrying numFramesToGenerate=2, one reconfigure.
		controls.cycleFgMultiplier()
		assertEquals(2, harness.runtime.fgMultiplier, "the runtime must land on 3x")
		assertEquals(listOf(2), calls.setValues, "3x records numFramesToGenerate=2")
		assertEquals(1, invalidations, "a real change invalidates the surface configuration exactly once")
		assertTrue(announced.last().contains("fg on at 3x"), "the readout names the multiplier now in effect: ${announced.last()}")

		// 3x -> 4x: the next value above, one record, one reconfigure.
		controls.cycleFgMultiplier()
		assertEquals(3, harness.runtime.fgMultiplier, "the runtime must land on 4x")
		assertEquals(listOf(2, 3), calls.setValues, "4x records numFramesToGenerate=3")
		assertEquals(2, invalidations, "each real change invalidates exactly once more")
		assertTrue(announced.last().contains("fg on at 4x"), "the readout names the multiplier now in effect: ${announced.last()}")

		// At the ceiling the cycle wraps back to 2x: never an unsupported value.
		controls.cycleFgMultiplier()
		assertEquals(1, harness.runtime.fgMultiplier, "at the ceiling the cycle must wrap to 2x")
		assertEquals(listOf(2, 3, 1), calls.setValues, "the wrap records numFramesToGenerate=1, nothing above the ceiling")
		assertEquals(3, invalidations, "the wrap is a real change and invalidates exactly once")
		assertTrue(announced.last().contains("fg on at 2x"), "the readout names the wrapped multiplier: ${announced.last()}")

		// The whole walk offered only values the device supports: 1..max, each recorded once.
		assertEquals(
			3,
			calls.setValues.distinct().size,
			"every recorded value must be distinct - the cycle never repeats a change mid-walk",
		)
	}

	@Test
	fun `a refused native record leaves the multiplier, the reconfigure count, and the readout unchanged`() {
		val calls = MultiplierNative(max = 3, setResult = REFUSED)
		var invalidations = 0
		val harness = harness(calls, invalidateSurfaceConfiguration = { invalidations++ })
		val controls = RuntimeControls(harness.runtime, announced::add)

		controls.cycleFgMultiplier()
		assertEquals(1, harness.runtime.fgMultiplier, "a refused record must leave the runtime on 2x")
		assertEquals(0, invalidations, "a refused record must invalidate nothing")
		assertTrue(announced.last().contains("at 2x"), "the readout must report the multiplier still in effect: ${announced.last()}")

		// The refused value is not retained as a change to repeat: the next cycle asks again.
		controls.cycleFgMultiplier()
		assertEquals(1, harness.runtime.fgMultiplier, "a refused value must never stick")
		assertEquals(0, invalidations, "no refusal invalidates anything")
	}

	@Test
	fun `a 2x-only device is a no-op cycle that records and invalidates nothing`() {
		val calls = MultiplierNative(max = 1)
		var invalidations = 0
		val harness = harness(calls, invalidateSurfaceConfiguration = { invalidations++ })
		val controls = RuntimeControls(harness.runtime, announced::add)

		controls.cycleFgMultiplier()
		assertEquals(emptyList<Int>(), calls.setValues, "a 2x ceiling must offer no other multiplier")
		assertEquals(0, invalidations, "a no-op cycle must invalidate nothing")
		assertTrue(announced.last().contains("at 2x"), "the readout stays on 2x: ${announced.last()}")
	}

	@Test
	fun `a cycle without a bridge answer changes nothing`() {
		var invalidations = 0
		val runtime = RenderRuntime(
			session = session(),
			sceneTarget = SceneTarget(
				allocate = { width, height -> FakeTarget(width, height) },
				release = {},
			),
			startup = { null },
			bridge = object : TestSessionBridge() {
				override fun setFgMultiplier(numFramesToGenerate: Int): Boolean =
					error("no bridge answer must never reach the record")
			},
			invalidateSurfaceConfiguration = { invalidations++ },
		)
		val controls = RuntimeControls(runtime, announced::add)

		controls.cycleFgMultiplier()
		assertEquals(1, runtime.fgMultiplier, "an unanswered query must leave the multiplier alone")
		assertEquals(0, invalidations, "an unanswered query must invalidate nothing")
		assertTrue(announced.last().contains("at 2x"), "the readout reports the multiplier still in effect: ${announced.last()}")
	}

	@Test
	fun `the acceptance record reports the active multiplier, not a fixed 2x`() {
		val calls = MultiplierNative(max = 2)
		val harness = harness(calls)
		val controls = RuntimeControls(harness.runtime, announced::add)

		// Explicit record: the field carries whatever multiplier it is handed.
		val explicit = record(fgMultiplier = 3)
		assertTrue(explicit.contains("fg-multiplier=4x"), explicit)

		// The cycle updates the record's active multiplier, so the default record follows it.
		controls.cycleFgMultiplier()
		val active = record()
		assertTrue(active.contains("fg-multiplier=3x"), active)

		// The 2x default survives as the contract's starting record.
		AcceptanceRecord.activeFgMultiplier = 1
		assertTrue(record().contains("fg-multiplier=2x"), record())
	}

	@Test
	fun `the adapter records and queries the multiplier without latching the session`() {
		val native = AdapterNative()
		val session = session()
		val adapter = LifecycleAdapter(session, native)

		// Not READY yet: neither call may reach the bridge.
		assertFalse(adapter.setFgMultiplier(2), "a session that is not READY must not record a multiplier")
		assertNull(adapter.queryFgMultiplier(), "a session that is not READY must not answer the query")
		assertEquals(emptyList<Int>(), native.setValues)

		assertTrue(adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")) != null)
		assertEquals(FgMultiplier(1, 3), adapter.queryFgMultiplier(), "a READY session answers the stored multiplier and the ceiling")
		assertTrue(adapter.setFgMultiplier(2), "a READY session records the multiplier")
		assertEquals(listOf(2), native.setValues, "the record passes numFramesToGenerate through to the bridge")

		// A refused record is invisible to the session: the cycle is a live control, not a
		// session stage, so the SR session must stay READY for the next cycle to try again.
		native.setResult = REFUSED
		assertFalse(adapter.setFgMultiplier(3), "a refused record must answer false")
		assertEquals(DlssSessionState.READY, session.state, "a refused record must not latch the SR session")
	}

	/** Builds the controls boundary over a recording fake and the multiplier seams. */
	private fun harness(
		calls: MultiplierNative,
		invalidateSurfaceConfiguration: () -> Unit = {},
	): Harness {
		val runtime = RenderRuntime(
			session = session(),
			sceneTarget = SceneTarget(
				allocate = { width, height -> FakeTarget(width, height) },
				release = {},
			),
			startup = { null },
			bridge = object : TestSessionBridge() {
				override fun queryFgMultiplier(): FgMultiplier = calls.multiplier()

				override fun setFgMultiplier(numFramesToGenerate: Int): Boolean =
					calls.set(numFramesToGenerate) == NativeApi.SUCCESS_RESULT
			},
			invalidateSurfaceConfiguration = invalidateSurfaceConfiguration,
		)
		return Harness(runtime)
	}

	@AfterEach
	fun restoreRecord() {
		AcceptanceRecord.activeFgMultiplier = 1
	}

	private fun session() = DlssSession(
		DlssStartupConfig(
			enabled = true,
			qualityMode = SRMode.QUALITY,
			outputDimensions = Dimensions(2560, 1440),
			sdkPath = null,
			nativeLibraryPath = null,
			dataPath = null,
			warnings = emptyList(),
		),
	)

	private fun record(fgMultiplier: Int? = null): String {
		val render: (Int?) -> String = { multiplier ->
			AcceptanceRecord.render(
				minecraftBuild = "26.2",
				enabled = true,
				state = DlssSessionState.READY,
				qualityMode = SRMode.QUALITY,
				renderPreset = SRMode.QUALITY.defaultPreset,
				outputDimensions = Dimensions(2560, 1440),
				renderDimensions = Dimensions(1706, 960),
				fgMultiplier = multiplier ?: AcceptanceRecord.activeFgMultiplier,
			)
		}
		return if (fgMultiplier == null) {
			// The no-argument call, so the record reads the active holder the way the
			// production readout emits it.
			AcceptanceRecord.render(
				minecraftBuild = "26.2",
				enabled = true,
				state = DlssSessionState.READY,
				qualityMode = SRMode.QUALITY,
				renderPreset = SRMode.QUALITY.defaultPreset,
				outputDimensions = Dimensions(2560, 1440),
				renderDimensions = Dimensions(1706, 960),
			)
		} else {
			render(fgMultiplier)
		}
	}

	private class Harness(
		val runtime: RenderRuntime,
	)

	private val announced = mutableListOf<String>()

	/**
	 * The fake bridge half of the cycle: answers the stored multiplier and the device
	 * ceiling, and records every multiplier set call - succeeding by default and storing
	 * the value like the native record does, so consecutive cycles walk against the truth
	 * rather than a frozen answer.
	 */
	private class MultiplierNative(
		private var current: Int = 1,
		private val max: Int = 3,
		var setResult: Int = NativeApi.SUCCESS_RESULT,
	) : NativeApi {
		val setValues = mutableListOf<Int>()

		fun multiplier(): FgMultiplier = FgMultiplier(current, max)

		fun set(numFramesToGenerate: Int): Int {
			setValues += numFramesToGenerate
			if (setResult == NativeApi.SUCCESS_RESULT) {
				current = numFramesToGenerate
			}
			return setResult
		}

		override fun setFgMultiplier(numFramesToGenerate: Int): Int = set(numFramesToGenerate)

		override fun queryFgMultiplier(): FgMultiplier = multiplier()

		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		): Int = error("unexpected initialize")

		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int): Dimensions =
			error("unexpected queryOptimalDimensions")

		override fun configure(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		): Int = error("unexpected configure")

		override fun acquireImages(): EvaluationImages = error("unexpected acquireImages")
		override fun releaseImages(): Int = error("unexpected releaseImages")
		override fun waitDeviceIdle(): Int = error("unexpected waitDeviceIdle")
		override fun frameTimings(): FrameTimings? = error("unexpected frameTimings")
		override fun writeMotion(request: MotionRequest): Int = error("unexpected writeMotion")
		override fun fillVelocity(request: FillVelocityRequest): Int = error("unexpected fillVelocity")
		override fun presentOutput(target: PresentTarget): Int = error("unexpected presentOutput")
		override fun evaluate(request: EvaluationRequest): Int = error("unexpected evaluate")
		override fun tagSrResources(request: SrTagRequest): Int = error("unexpected tagSrResources")
	}

	/** Records the multiplier seams and answers the three calls [LifecycleAdapter.initialize] drives. */
	private class AdapterNative : NativeApi {
		var setResult = NativeApi.SUCCESS_RESULT
		val setValues = mutableListOf<Int>()

		override fun setFgMultiplier(numFramesToGenerate: Int): Int {
			setValues += numFramesToGenerate
			return setResult
		}

		override fun queryFgMultiplier(): FgMultiplier = FgMultiplier(1, 3)

		override fun initialize(
			vkInstance: Long,
			vkPhysicalDevice: Long,
			vkDevice: Long,
			sdkPath: Path,
			dataPath: Path,
		): Int = NativeApi.SUCCESS_RESULT

		override fun queryOptimalDimensions(outputWidth: Int, outputHeight: Int, qualityMode: Int) =
			Dimensions(1280, 720)

		override fun configure(
			outputWidth: Int,
			outputHeight: Int,
			renderWidth: Int,
			renderHeight: Int,
			qualityMode: Int,
			renderPreset: Int,
		): Int = NativeApi.SUCCESS_RESULT

		override fun acquireImages(): EvaluationImages = error("unexpected acquireImages")
		override fun releaseImages(): Int = error("unexpected releaseImages")
		override fun waitDeviceIdle(): Int = error("unexpected waitDeviceIdle")
		override fun frameTimings(): FrameTimings? = error("unexpected frameTimings")
		override fun writeMotion(request: MotionRequest): Int = error("unexpected writeMotion")
		override fun fillVelocity(request: FillVelocityRequest): Int = error("unexpected fillVelocity")
		override fun presentOutput(target: PresentTarget): Int = error("unexpected presentOutput")
		override fun evaluate(request: EvaluationRequest): Int = error("unexpected evaluate")
		override fun tagSrResources(request: SrTagRequest): Int = error("unexpected tagSrResources")
	}

	/** Render target with no GPU buffers, so the runtime is testable off the render thread. */
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

	private companion object {
		/** Any non-success native result: the record refused without naming which refusal. */
		const val REFUSED = NativeApi.SUCCESS_RESULT + 1
	}
}
