package me.snowmii.dlss.readout
import me.snowmii.dlss.bridge.DlssFrameTimings
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.bridge.FgState
import me.snowmii.dlss.session.DlssFrameDecision
import me.snowmii.dlss.session.DlssFrameRoute
import me.snowmii.dlss.session.SRMode
import me.snowmii.dlss.session.SRModelPreset
import me.snowmii.dlss.session.DlssSessionState
import com.mojang.blaze3d.pipeline.RenderTarget
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft

/**
 * Formats and emits everything a reviewer reads about one session: the first world-phase line,
 * the world frame-rate line, the acceptance record, and the first-evaluation line.
 *
 * The world phase and the evaluation own *when* those events happen - this module owns how they
 * are spelled and where they go. It is fed by events and carries no window or compose logic,
 * which is the whole split: the phase's reporting state (one-shot flags, the sampling window)
 * was orthogonal to the redirect window it sat in, and three of the phase's injected seams
 * existed only to serve it.
 *
 * The frame-rate sampling counts the world frames themselves, not Minecraft's own FPS counter,
 * so the same number is comparable between a DLSS session and a `mc.dlss.enabled=false`
 * session. Without it, "frame rate feels bad" cannot be separated from a dev client simply
 * being slow.
 */
class SessionReadout(
	private val emit: (String) -> Unit,
	private val minecraftBuild: () -> String? = { null },
	private val probeResolvedTarget: (() -> RenderTarget?)? = null,
) {
	private var reportedFirstPhase = false
	private var reportedFirstEvaluation = false
	private var sampleStartedAt = 0L
	private var sampledFrames = 0

	// The temporal-accumulation half of the sample window. A DLSS image that stays aliased while
	// the camera holds still means the accumulation never converged, and only two inputs can
	// cause that from this side: a jitter sequence that stopped varying (every frame samples the
	// same sub-pixel point, so there is no new information to accumulate) or a history reset
	// arriving every frame (each frame is upscaled alone). Both are invisible in the frame rate
	// and neither is observable from the image alone, so the sample window counts them.
	private val sampledJitterIndices = HashSet<Int>()
	private var sampledJitterFrames = 0
	private var sampledResets = 0
	private var lastJitterPixelX = 0f
	private var lastJitterPixelY = 0f

	/**
	 * Records one evaluated frame's jitter phase and history-reset flag into the current sample
	 * window. Called per DLSS evaluation; frames that never evaluate contribute nothing.
	 */
	fun recordFrameJitter(index: Int, pixelX: Float, pixelY: Float, reset: Boolean) {
		sampledJitterIndices.add(index)
		sampledJitterFrames++
		if (reset) {
			sampledResets++
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
			sampledJitterIndices.size,
			sampledJitterFrames,
			sampledResets,
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
	fun worldPhase(
		mainTarget: RenderTarget,
		scene: RenderTarget?,
		frame: DlssFrameDecision?,
		facts: SessionFacts,
		frameTimings: () -> DlssFrameTimings?,
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
	fun firstEvaluation(
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
		emit(
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
		if (reportedFirstPhase) {
			return
		}

		reportedFirstPhase = true
		// What the renderer actually resolves mid-phase. If this is not the scene target, the
		// route decided correctly but the redirect never reached the frame graph.
		val resolved = probeResolvedTarget?.invoke()
		emit(
			"DLSS first world phase: main=${mainTarget.width}x${mainTarget.height}" +
				" route=${frame?.route ?: DlssFrameRoute.VANILLA}" +
				" reason=${frame?.reason ?: "startup-unavailable"}" +
				" render=${facts.renderDimensions ?: "none"}" +
				" scene=${scene?.let { "${it.width}x${it.height}" } ?: "none"}" +
				" resolved=${resolved?.let { "${it.width}x${it.height}" } ?: "unprobed"}" +
				" redirected=${resolved != null && resolved === scene}",
		)
		reportAcceptanceRecord(facts)
	}

	/**
	 * Counts world phases and reports the rate every few seconds.
	 */
	private fun sampleWorldFrameRate(
		scene: RenderTarget?,
		frame: DlssFrameDecision?,
		frameTimings: () -> DlssFrameTimings?,
		fgState: () -> FgState?,
		pacing: () -> String?,
	) {
		val now = System.nanoTime()
		if (sampleStartedAt == 0L) {
			sampleStartedAt = now
			return
		}

		sampledFrames++
		val elapsed = now - sampleStartedAt
		if (elapsed < SAMPLE_INTERVAL_NANOS) {
			return
		}

		val fps = sampledFrames * 1_000_000_000.0 / elapsed
		// The GPU cost of the chain belongs on the same line as the frame rate: separately they
		// are two numbers that move for unrelated reasons, and together they are the comparison -
		// a frame rate that did not change while the chain costs a millisecond is a client whose
		// frames are bounded by something other than the GPU.
		emit(
			"DLSS world frame rate: %.1f fps over %d frames, route=%s, world=%s, gpu=%s%s%s%s".format(
				fps,
				sampledFrames,
				frame?.route ?: DlssFrameRoute.VANILLA,
				scene?.let { "${it.width}x${it.height}" } ?: "main-target",
				frameTimings() ?: "unmeasured",
				fgMonitorSuffix(fgState(), fps),
				pacing() ?: "",
				accumulationSuffix(),
			),
		)
		sampleStartedAt = now
		sampledFrames = 0
		sampledJitterIndices.clear()
		sampledJitterFrames = 0
		sampledResets = 0
	}

	/**
	 * Reports the environment half of the Sprint acceptance record.
	 *
	 * Emitted from the first world phase rather than mod init, because the internal resolution is
	 * the field the reviewer most needs and NGX does not choose it until startup has run, which
	 * the first frame that asks for a world target is what drives.
	 */
	private fun reportAcceptanceRecord(facts: SessionFacts) {
		emit(
			AcceptanceRecord.render(
				minecraftBuild = minecraftBuild(),
				enabled = facts.enabled,
				state = facts.state,
				// The runtime's mode and preset rather than the configuration's: a reviewer who
				// switched either one before the first world frame is holding the record for a
				// session that is not running what it started as.
				qualityMode = facts.qualityMode,
				renderPreset = facts.renderPreset,
				outputDimensions = facts.outputDimensions,
				renderDimensions = facts.renderDimensions,
			),
		)
	}

	companion object {
		private const val SAMPLE_INTERVAL_NANOS = 5_000_000_000L

		/**
		 * The DLSS-G monitor suffix for the frame-rate line: actual presented FPS, status word,
		 * and input-processing completion fence. Streamline reports the number of real plus
		 * generated presentations per app frame, so its documented calculation is app FPS times
		 * that value. A factor of two is the 2x-generation proof; an advancing fence proves the
		 * plugin is reading presented frames' tagged inputs.
		 */
		fun fgMonitorSuffix(fg: FgState?, appFps: Double): String = if (fg == null) {
			""
		} else {
			", fg=presented=%.1f status=%d fence=%d".format(
				appFps * fg.numFramesPresented,
				fg.status,
				fg.lastPresentInputsProcessingFenceValue,
			)
		}

		/** Formats and drops, for tests that assert on the phase's own behavior. */
		val NOOP: SessionReadout = SessionReadout({})

		/**
		 * The Minecraft build as the loader reports it, or null when it cannot be read.
		 *
		 * Asked of the loader rather than `SharedConstants`, whose shape moves between versions.
		 * A record field is worth no exception on the render thread, so a failure degrades to the
		 * reviewer filling the line in by hand.
		 */
		private fun loaderMinecraftBuild(): String? = try {
			FabricLoader.getInstance()
				.getModContainer("minecraft")
				.map { container -> container.metadata.version.friendlyString }
				.orElse(null)
		} catch (_: Throwable) {
			null
		}

		/** Production wiring: the loader build and a real resolution probe behind the lines. */
		@JvmStatic
		fun forMinecraft(diagnostics: (String) -> Unit): SessionReadout = SessionReadout(
			emit = diagnostics,
			minecraftBuild = ::loaderMinecraftBuild,
			probeResolvedTarget = { Minecraft.getInstance().gameRenderer.mainRenderTarget() },
		)
	}
}

/**
 * Runtime facts the acceptance record names, fed by the phase from the runtime rather than read
 * directly, so the readout stays decoupled from the runtime's module.
 */
class SessionFacts(
	val enabled: Boolean,
	val state: DlssSessionState,
	val qualityMode: SRMode,
	val renderPreset: SRModelPreset,
	val outputDimensions: DlssDimensions,
	val renderDimensions: DlssDimensions?,
)
