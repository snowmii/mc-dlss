package me.snowmii.dlss.client

/**
 * Video-settings locks driven by the live session. No session (title screen, SR never started)
 * means nothing is locked.
 */
object VideoOptionLocks {
	/** RGSS is unavailable in the Texture Filtering cycle while Super Resolution is on. */
	@JvmStatic
	fun srLocksRgss(): Boolean = ClientRuntime.active().activeControls()?.enabled == true

	/** Inverse of [srLocksRgss], for vanilla's alt-list cycle supplier. */
	@JvmStatic
	fun rgssAllowed(): Boolean = !srLocksRgss()

	/**
	 * V-sync cannot be changed while the user has frame generation armed and the device does not
	 * support FG V-sync. When Streamline DLSS-G reports bIsVsyncSupportAvailable, V-sync remains unlocked.
	 */
	@JvmStatic
	fun fgLocksVsync(): Boolean =
		ClientRuntime.active().activeControls()?.frameGenerationEnabled == true &&
			!ClientRuntime.active().isFgVsyncSupported()
}
