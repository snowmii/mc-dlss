package me.snowmii.dlss.client

object VideoOptionLocks {
	@JvmStatic
	fun srLocksRgss(): Boolean = ClientRuntime.active().activeControls()?.enabled == true

	@JvmStatic
	fun rgssAllowed(): Boolean = !srLocksRgss()

	@JvmStatic
	fun fgLocksVsync(): Boolean =
		ClientRuntime.active().activeControls()?.frameGenerationEnabled == true &&
			!ClientRuntime.active().isFgVsyncSupported()
}
