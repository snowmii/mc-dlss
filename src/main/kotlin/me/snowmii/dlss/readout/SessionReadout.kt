package me.snowmii.dlss.readout
import me.snowmii.streamline.FrameTimings
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.FgState
import me.snowmii.dlss.DlssFrameDecision
import me.snowmii.dlss.DlssFrameRoute
import me.snowmii.dlss.SRMode
import me.snowmii.dlss.SRModelPreset
import me.snowmii.dlss.DlssSessionState
import com.mojang.blaze3d.pipeline.RenderTarget
import me.snowmii.dlss.ModEntry
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory

/**
 * Formats and emits the session's world-phase, frame-rate, environment, and evaluation lines.
 *
 * The world phase and evaluation own when events happen; this module owns formatting and output.
 * It carries no target, window, or composition state.
 *
 * The frame-rate sampling counts the world frames themselves, not Minecraft's own FPS counter,
 * so the same number is comparable between a DLSS session and a `mc.dlss.enabled=false`
 * session. Without it, "frame rate feels bad" cannot be separated from a dev client simply
 * being slow.
 */
class SessionReadout(
	private val emitLine: (String) -> Unit,
	private val resolveReportedTarget: (() -> RenderTarget?)? = null,
) {
	private var firstPhaseReported = false
	private var reportedFirstEvaluation = false
	private var sampleStartedAt = 0L
	private var sampledWorldFrames = 0

	// The temporal-accumulation half of the sample window. A DLSS image that stays aliased while
	// the camera holds still means the accumulation never converged, and only two inputs can
	// cause that from this side: a jitter sequence that stopped varying (every frame samples the
	// same sub-pixel point, so there is no new information to accumulate) or a history reset
	// arriving every frame (each frame is upscaled alone). Both are invisible in the frame rate
	// and neither is observable from the image alone, so the sample window counts them.
	private val sampledJitterPhases = HashSet<Int>()
	private var sampledJitterFrames = 0
	private var sampledHistoryResets = 0
	private var lastJitterPixelX = 0f
	private var lastJitterPixelY = 0f

	/**
	 * Records one evaluated frame's jitter phase and history-reset flag into the current sample
	 * window. Called per DLSS evaluation; frames that never evaluate contribute nothing.
	 */
	fun recordFrameJitter(index: Int, pixelX: Float, pixelY: Float, reset: Boolean) {
		sampledJitterPhases.add(index)
		sampledJitterFrames++
		if (reset) {
			sampledHistoryResets++
		}
		lastJitterPixelX = pixelX
		lastJitterPixelY = pixelY
	}

	/**
	 * The accumulation suffix for the sampled line, or empty when no frame evaluated in the
	 * window. `phases` is how many distinct jitter offsets the window used: a healthy window
	 * walks the whole sequence, and `phases=1` is a frozen sequence. `resets` should be zero for
	 * a window with no teleport, dimension change, or vanilla frame in it.
	 */
	private fun accumulationSuffix(): String {
		if (sampledJitterFrames == 0) {
			return ""
		}
		return ", accum=phases=%d/%d resets=%d/%d jitter=%.3f,%.3f".format(
			sampledJitterPhases.size,
			sampledJitterFrames,
			sampledHistoryResets,
			sampledJitterFrames,
			lastJitterPixelX,
			lastJitterPixelY,
		)
	}

	/**
	 * One world phase finished rendering: the first-phase line on the first call, the frame-rate
	 * line on the sampling boundary after that.
	 *
	 * [scene] is the target the world actually drew into (the low-resolution scene target on an
	 * eligible DLSS frame, the main target otherwise); [frame] the route the runtime decided;
	 * [facts] the runtime's mode, preset, and state the acceptance record names; and
	 * [frameTimings] the GPU-timings read, called only when the frame-rate line is about to
	 * report, because the answer crosses the ABI.
	 */
	fun reportWorldPhase(
		mainTarget: RenderTarget,
		scene: RenderTarget?,
		frame: DlssFrameDecision?,
		facts: SessionFacts,
		frameTimings: () -> FrameTimings?,
		fgState: () -> FgState? = { null },
		pacing: () -> String? = { null },
	) {
		reportFirstPhase(mainTarget, scene, frame, facts)
		sampleWorldFrameRate(scene, frame, frameTimings, fgState, pacing)
	}

	/**
	 * The session's first evaluation, recorded or not, exactly once.
	 *
	 * A recorded evaluation and a session that silently never reached one look identical from
	 * outside - the frame renders either way. The line names which stage the frame actually got
	 * through and the images it wrote into, which is enough to tell them apart from the log alone.
	 */
	fun reportFirstEvaluation(
		recorded: Boolean,
		colorImage: Long,
		depthImage: Long,
		motionImage: Long,
		outputImage: Long,
	) {
		if (reportedFirstEvaluation) {
			return
		}

		reportedFirstEvaluation = true
		emitLine(
			"DLSS first evaluation: recorded=$recorded" +
				" color=0x${colorImage.toString(16)}" +
				" depth=0x${depthImage.toString(16)}" +
				" motion=0x${motionImage.toString(16)}" +
				" output=0x${outputImage.toString(16)}",
		)
	}

	/**
	 * Reports the first world phase exactly once.
	 *
	 * Without this, an engaged DLSS route and a session that never started look identical from
	 * outside: both render a normal-looking frame and log nothing. The line names the measured
	 * main target, the route actually taken, the session's own reason for it, and the render
	 * dimensions, which is enough to tell those two apart from the log alone.
	 */
	private fun reportFirstPhase(
		mainTarget: RenderTarget,
		scene: RenderTarget?,
		frame: DlssFrameDecision?,
		facts: SessionFacts,
	) {
		if (firstPhaseReported) {
			return
		}

		firstPhaseReported = true
		// What the renderer actually resolves mid-phase. If this is not the scene target, the
		// route decided correctly but the redirect never reached the frame graph.
		val resolved = resolveReportedTarget?.invoke()
		emitLine(
			"DLSS first world phase: main=${mainTarget.width}x${mainTarget.height}" +
				" route=${frame?.route ?: DlssFrameRoute.VANILLA}" +
				" reason=${frame?.reason ?: "startup-unavailable"}" +
				" render=${facts.renderDimensions ?: "none"}" +
				" scene=${scene?.let { "${it.width}x${it.height}" } ?: "none"}" +
				" resolved=${resolved?.let { "${it.width}x${it.height}" } ?: "unprobed"}" +
				" redirected=${resolved != null && resolved === scene}",
		)
		}

	/**
	 * Counts world phases and reports the rate every few seconds.
	 */
	private fun sampleWorldFrameRate(
		scene: RenderTarget?,
		frame: DlssFrameDecision?,
		frameTimings: () -> FrameTimings?,
		fgState: () -> FgState?,
		pacing: () -> String?,
	) {
		val now = System.nanoTime()
		if (sampleStartedAt == 0L) {
			sampleStartedAt = now
			return
		}

		sampledWorldFrames++
		val elapsed = now - sampleStartedAt
		if (elapsed < SAMPLE_INTERVAL_NANOS) {
			return
		}

		val fps = sampledWorldFrames * 1_000_000_000.0 / elapsed
		// The GPU cost of the chain belongs on the same line as the frame rate: separately they
		// are two numbers that move for unrelated reasons, and together they are the comparison -
		// a frame rate that did not change while the chain costs a millisecond is a client whose
		// frames are bounded by something other than the GPU.
		emitLine(
			"DLSS world frame rate: %.1f fps over %d frames, route=%s, world=%s, gpu=%s%s%s".format(
				fps,
				sampledWorldFrames,
				frame?.route ?: DlssFrameRoute.VANILLA,
				scene?.let { "${it.width}x${it.height}" } ?: "main-target",
				frameTimings() ?: "unmeasured",
				fgMonitorSuffix(fgState()),
				accumulationSuffix(),
			),
		)
		pacing()?.let { emitLine("DLSS pacing: $it") }
		sampleStartedAt = now
		sampledWorldFrames = 0
		sampledJitterPhases.clear()
		sampledJitterFrames = 0
		sampledHistoryResets = 0
	}

	companion object {
		private const val SAMPLE_INTERVAL_NANOS = 5_000_000_000L

		/**
		 * The DLSS-G monitor suffix for the frame-rate line: status word and input-processing
		 * completion fence. Presented FPS lives on vanilla's F3 fps line instead.
		 */
		fun fgMonitorSuffix(fg: FgState?): String = if (fg == null) {
			""
		} else {
			", fg=status=%d fence=%d".format(
				fg.status,
				fg.lastPresentInputsProcessingFenceValue,
			)
		}

		/** Presented FPS next to vanilla's F3 fps line while generation is actually composing. */
		fun fgPresentedFpsSuffix(appFps: Int, fg: FgState): String =
			if (fg.numFramesPresented <= 1) "" else " fg ${appFps * fg.numFramesPresented}"

		/** The F3 status line: session state, FG, mode, preset, and resolutions. */
		fun statusLine(
			state: String,
			fg: String,
			multiplier: Int,
			mode: SRMode,
			preset: SRModelPreset,
			internal: String,
			output: Dimensions,
		): String = "DLSS $state" +
			" | fg $fg at ${multiplier}x" +
			" | mode ${mode.propertyValue}" +
			" | preset ${preset.propertyValue}" +
			" | internal $internal" +
			" | output $output"

		fun frameGenerationStatus(userEnabled: Boolean, effective: Boolean): String = when {
			!userEnabled -> "off"
			effective -> "on"
			else -> "on (suspended)"
		}

		/** Formats and drops, for tests that assert on the phase's own behavior. */
		val NOOP: SessionReadout = SessionReadout({})

		private val LOGGER = LoggerFactory.getLogger(ModEntry.MOD_ID)

		/**
		 * Console + F3 snapshot sink.
		 *
		 * Log emission is gated on [me.snowmii.dlss.client.ModConfig.UserSettings.debugLog]; the
		 * F3 snapshot always updates so the overlay stays live regardless of the log setting.
		 */
		@JvmStatic
		fun emit(message: String) {
			if (me.snowmii.dlss.client.ModConfig.user.debugLog) {
				LOGGER.info(message)
			}
			DlssDebugSnapshot.record(message)
		}

		/** Production wiring: log/snapshot sink and a real resolution probe behind the lines. */
		@JvmStatic
		fun forMinecraft(diagnostics: (String) -> Unit = ::emit): SessionReadout = SessionReadout(
			emitLine = diagnostics,
			resolveReportedTarget = { Minecraft.getInstance().gameRenderer.mainRenderTarget() },
		)
	}
}

/**
 * Runtime facts supplied by the phase so the readout stays decoupled from the runtime module.
 */
class SessionFacts(
	val enabled: Boolean,
	val state: DlssSessionState,
	val qualityMode: SRMode,
	val renderPreset: SRModelPreset,
	val outputDimensions: Dimensions,
	val renderDimensions: Dimensions?,
)
