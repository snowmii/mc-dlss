package me.snowmii.dlss.readout

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
