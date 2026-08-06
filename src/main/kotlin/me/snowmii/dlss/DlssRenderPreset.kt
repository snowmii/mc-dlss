package me.snowmii.dlss

/**
 * The DLSS Super Resolution model a session runs, in the values
 * `NVSDK_NGX_DLSS_Hint_Render_Preset` gives them.
 *
 * NGX picks a preset for every mode whether or not one is asked for, and which one it picks moves
 * with the DLL and with driver-side overrides. A session that never names a preset therefore
 * cannot say afterwards which model produced its frames, which is exactly what the acceptance
 * record has to state. So the preset is always written, and the mode's documented default is what
 * it is written to unless the reviewer asks for another.
 *
 * Only the presets SDK 310.7.0 documents as usable are here. A through D were removed from the
 * SDK, E and F are deprecated, and G through I and N and O revert to default behaviour, so none of
 * them is a choice this mod offers.
 */
enum class DlssRenderPreset(
	val ngxValue: Int,
	val propertyValue: String,
	val description: String,
) {
	K(11, "k", "transformer, best image quality, default for DLAA/Quality/Balanced"),
	J(10, "j", "transformer, less ghosting than K and more flicker"),
	L(12, "l", "transformer, default for Ultra Performance"),
	M(13, "m", "transformer, default for Performance"),
	;

	companion object {
		fun fromPropertyValue(value: String): DlssRenderPreset? =
			entries.firstOrNull { it.propertyValue == value }
	}
}
