package me.snowmii.dlss.client

/** Latest durable diagnostic from each F3-kept DLSS readout category. */
object DlssDebugSnapshot {
	@Volatile
	private var performance: List<String> = emptyList()
	@Volatile
	private var frameGeneration: List<String> = emptyList()
	@Volatile
	private var compatibility: List<String> = emptyList()

	fun record(message: String) {
		when {
			message.startsWith("DLSS world frame rate:") -> performance = listOf(shortFps(message))
			message.startsWith("Frame generation resumed") -> frameGeneration = listOf("FG resumed")
			message.startsWith("Frame generation suspended") -> frameGeneration = listOf("FG suspended")
			message.startsWith("DLSS motion vectors:") -> compatibility = listOf(
				message.removePrefix("DLSS motion vectors: ").let { "motion: $it" },
			)
		}
	}

	fun lines(): List<String> = compatibility + performance + frameGeneration

	internal fun clear() {
		performance = emptyList()
		frameGeneration = emptyList()
		compatibility = emptyList()
	}

	private fun shortFps(message: String): String {
		val fps = """([\d.]+) fps""".toRegex().find(message)?.groupValues?.get(1)?.substringBefore('.')
		val route = """route=([^,]+)""".toRegex().find(message)?.groupValues?.get(1)
		val gpu = """gpu=total=([^,\s]+)""".toRegex().find(message)?.groupValues?.get(1)
			?: """gpu=([^,]+)""".toRegex().find(message)?.groupValues?.get(1)
		val resets = """resets=(\d+)/""".toRegex().find(message)?.groupValues?.get(1)
		return buildString {
			append("DLSS ")
			append(fps ?: "?")
			append(" fps")
			if (route != null) append("  $route")
			if (gpu != null) append("  gpu $gpu")
			if (resets != null) append("  resets $resets")
		}
	}
}
