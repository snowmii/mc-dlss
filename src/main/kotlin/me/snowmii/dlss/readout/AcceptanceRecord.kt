package me.snowmii.dlss.readout
import me.snowmii.streamline.Dimensions
import me.snowmii.dlss.session.SRMode
import me.snowmii.dlss.session.SRModelPreset
import me.snowmii.dlss.session.DlssSessionState
/**
 * The environment half of the Sprint acceptance record.
 *
 * `docs/sprint-acceptance.md#Required-PR-record` requires the reviewer to record the reviewer's
 * own identity, the candidate commit, the Minecraft build, GPU and driver, the Streamline version
 * and pinned plugin set, internal and output resolutions, the DLSS quality mode, the FG
 * multiplier, every checklist result, and an overall result. Several of
 * those are facts the running process already holds, and a reviewer transcribing them by hand is
 * the step most likely to be wrong: the internal resolution in particular is NGX-chosen, never
 * appears in any setting, and cannot be read off the screen.
 *
 * So the process reports what it knows, in the order the document asks for it, and marks the rest
 * [REVIEWER_SUPPLIED] rather than guessing or omitting the line. A blank the reviewer must fill is
 * visible in the log; a missing line is not.
 *
 * Every field is a parameter because the record is one fact away from being untestable otherwise:
 * the render dimensions live on the runtime, the mode on the startup config, and the Minecraft
 * build behind a loader call that needs a running client.
 */
object AcceptanceRecord {
	/** Stands in for a field only the live reviewer can fill. */
	const val REVIEWER_SUPPLIED = "<reviewer>"

	/** Stands in for a field the process should hold but does not, which is itself a finding. */
	const val UNAVAILABLE = "none"

	/** The pinned Streamline stack from the contract, recorded rather than queried at runtime. */
	const val STREAMLINE_VERSION = "2.12.0"

	/** The pinned plugin set the contract's validation baseline requires next to the runtime. */
	const val PINNED_PLUGIN_SET = "sl.dlss,sl.dlss_g,sl.reflex,sl.interposer"

	/** The contract's default multiplier: one generated frame per rendered frame. */
	const val FG_MULTIPLIER = "2x"

	/**
	 * The FG multiplier currently in effect, in `numFramesToGenerate` units (1 = 2x, 2 = 3x,
	 * and so on), updated by the runtime when a multiplier cycle lands. The record reads this
	 * by default, so the `fg-multiplier` field names the active multiplier rather than a
	 * fixed 2x.
	 */
	@Volatile
	var activeFgMultiplier: Int = 1

	const val HEADING = "DLSS acceptance record (docs/sprint-acceptance.md#Required-PR-record)"

	/**
	 * Renders the record as one multi-line block.
	 *
	 * [renderDimensions] is null until a successful NGX startup, and stays null for a session that
	 * never reached one. That is not an error here: the record reports it as [UNAVAILABLE], which
	 * tells the reviewer the internal resolution does not exist rather than that it went unread.
	 */
	fun render(
		minecraftBuild: String?,
		enabled: Boolean,
		state: DlssSessionState,
		qualityMode: SRMode,
		renderPreset: SRModelPreset,
		outputDimensions: Dimensions,
		renderDimensions: Dimensions?,
		/**
		 * The FG multiplier in `numFramesToGenerate` units (1 = 2x), defaulting to
		 * [activeFgMultiplier] so the record reports the multiplier actually in effect.
		 */
		fgMultiplier: Int = activeFgMultiplier,
	): String = buildString {
		append(HEADING)
		appendField("reviewer", REVIEWER_SUPPLIED)
		appendField("candidate-commit", REVIEWER_SUPPLIED)
		appendField("gpu-driver", REVIEWER_SUPPLIED)
		appendField("streamline-version", STREAMLINE_VERSION)
		appendField("streamline-plugins", PINNED_PLUGIN_SET)
		appendField("minecraft-build", minecraftBuild ?: REVIEWER_SUPPLIED)
		appendField("dlss-enabled", enabled.toString())
		appendField("dlss-state", state.name)
		appendField("quality-mode", qualityMode.propertyValue)
		// The model behind the frames. NGX runs a preset whether or not one is asked for, so a
		// record naming only the mode names half the configuration that produced the image.
		appendField("render-preset", renderPreset.propertyValue)
		appendField("output-resolution", outputDimensions.toString())
		appendField("internal-resolution", renderDimensions?.toString() ?: UNAVAILABLE)
		appendField("fg-multiplier", "${fgMultiplier + 1}x")
		appendField("checklist-result", REVIEWER_SUPPLIED)
		appendField("overall-result", REVIEWER_SUPPLIED)
	}

	private fun StringBuilder.appendField(name: String, value: String) {
		append("\n  ").append(name).append('=').append(value)
	}
}
