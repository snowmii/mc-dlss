package me.snowmii.dlss.readout

/** Latest durable FG diagnostic kept on F3. World-frame-rate and motion-vector lines stay in the log. */
object DlssDebugSnapshot {
	@Volatile
	private var frameGeneration: List<String> = emptyList()

	fun record(message: String) {
		when {
			message.startsWith("Frame generation resumed") -> frameGeneration = listOf("FG resumed")
			message.startsWith("Frame generation suspended") -> frameGeneration = listOf("FG suspended")
		}
	}

	fun lines(): List<String> = frameGeneration

	internal fun clear() {
		frameGeneration = emptyList()
	}
}
