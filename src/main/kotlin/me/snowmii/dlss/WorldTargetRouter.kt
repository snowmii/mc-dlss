package me.snowmii.dlss

/** Dimension policy consumed by renderer hooks before world target allocation. */
data class WorldTargetRoute(
	val frame: DlssFrameDecision,
	val worldDimensions: DlssDimensions,
	val mainTargetDimensions: DlssDimensions,
)

class WorldTargetRouter(
	private val session: DlssSession,
	private val renderDimensions: DlssDimensions,
) {
	init {
		require(renderDimensions.width > 0 && renderDimensions.height > 0) {
			"Render dimensions must be positive"
		}
	}

	fun route(normalInWorldFrame: Boolean, outputDimensions: DlssDimensions): WorldTargetRoute {
		val frame = session.beginFrame(normalInWorldFrame, outputDimensions)
		val worldDimensions = if (frame.route == DlssFrameRoute.DLSS) renderDimensions else outputDimensions
		return WorldTargetRoute(
			frame = frame,
			worldDimensions = worldDimensions,
			mainTargetDimensions = outputDimensions,
		)
	}
}
