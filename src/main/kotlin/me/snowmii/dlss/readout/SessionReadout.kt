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

class SessionReadout(
	private val emitLine: (String) -> Unit,
	private val resolveReportedTarget: (() -> RenderTarget?)? = null,
) {
	private var firstPhaseReported = false
	private var reportedFirstEvaluation = false
	private var sampleStartedAt = 0L
	private var sampledWorldFrames = 0

	private val sampledJitterPhases = HashSet<Int>()
	private var sampledJitterFrames = 0
	private var sampledHistoryResets = 0
	private var lastJitterPixelX = 0f
	private var lastJitterPixelY = 0f

	fun recordFrameJitter(index: Int, pixelX: Float, pixelY: Float, reset: Boolean) {
		sampledJitterPhases.add(index)
		sampledJitterFrames++
		if (reset) {
			sampledHistoryResets++
		}
		lastJitterPixelX = pixelX
		lastJitterPixelY = pixelY
	}

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

		fun fgMonitorSuffix(fg: FgState?): String = if (fg == null) {
			""
		} else {
			", fg=status=%d fence=%d".format(
				fg.status,
				fg.lastPresentInputsProcessingFenceValue,
			)
		}

		fun fgPresentedFpsSuffix(appFps: Int, fg: FgState): String =
			if (fg.numFramesPresented <= 1) "" else " fg ${appFps * fg.numFramesPresented}"

		fun statusLine(
			state: String,
			fg: String,
			multiplier: Int,
			mode: SRMode,
			preset: SRModelPreset,
			internal: String,
			output: Dimensions,
			rr: String? = null,
			reflex: String? = null,
		): String {
			val line = StringBuilder("DLSS $state")
				.append(" | fg $fg at ${multiplier}x")
				.append(" | mode ${mode.propertyValue}")
				.append(" | preset ${preset.propertyValue}")
			if (rr != null) line.append(" | rr $rr")
			if (reflex != null) line.append(" | reflex $reflex")
			line.append(" | internal $internal")
				.append(" | output $output")
			return line.toString()
		}

		fun frameGenerationStatus(userEnabled: Boolean, effective: Boolean): String = when {
			!userEnabled -> "off"
			effective -> "on"
			else -> "on (suspended)"
		}

		val NOOP: SessionReadout = SessionReadout({})

		private val LOGGER = LoggerFactory.getLogger(ModEntry.MOD_ID)

		@JvmStatic
		fun emit(message: String) {
			if (me.snowmii.dlss.client.ModConfig.user.debugLog) {
				LOGGER.info(message)
			}
			DlssDebugSnapshot.record(message)
		}

		@JvmStatic
		fun forMinecraft(diagnostics: (String) -> Unit = ::emit): SessionReadout = SessionReadout(
			emitLine = diagnostics,
			resolveReportedTarget = { Minecraft.getInstance().gameRenderer.mainRenderTarget() },
		)
	}
}

class SessionFacts(
	val enabled: Boolean,
	val state: DlssSessionState,
	val qualityMode: SRMode,
	val renderPreset: SRModelPreset,
	val outputDimensions: Dimensions,
	val renderDimensions: Dimensions?,
)
