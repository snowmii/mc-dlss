package me.snowmii.dlss

/**
 * The environment half of the Sprint acceptance record.
 *
 * `docs/sprint-acceptance.md#Required-PR-record` requires the reviewer to record the reviewer's
 * own identity, the candidate commit, the Minecraft build, GPU and driver, internal and output
 * resolutions, the DLSS quality mode, every checklist result, and an overall result. Several of
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
object DlssAcceptanceRecord {
	/** Stands in for a field only the live reviewer can fill. */
	const val REVIEWER_SUPPLIED = "<reviewer>"

	/** Stands in for a field the process should hold but does not, which is itself a finding. */
	const val UNAVAILABLE = "none"

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
		qualityMode: DlssQualityMode,
		outputDimensions: DlssDimensions,
		renderDimensions: DlssDimensions?,
	): String = buildString {
		append(HEADING)
		appendField("reviewer", REVIEWER_SUPPLIED)
		appendField("candidate-commit", REVIEWER_SUPPLIED)
		appendField("gpu-driver", REVIEWER_SUPPLIED)
		appendField("minecraft-build", minecraftBuild ?: REVIEWER_SUPPLIED)
		appendField("dlss-enabled", enabled.toString())
		appendField("dlss-state", state.name)
		appendField("quality-mode", qualityMode.propertyValue)
		appendField("output-resolution", outputDimensions.toString())
		appendField("internal-resolution", renderDimensions?.toString() ?: UNAVAILABLE)
	}

	private fun StringBuilder.appendField(name: String, value: String) {
		append("\n  ").append(name).append('=').append(value)
	}
}
