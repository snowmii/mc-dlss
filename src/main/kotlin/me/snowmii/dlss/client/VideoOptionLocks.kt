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
	 * V-sync cannot be changed while the user has frame generation armed. The stored option is
	 * not written; FG already reads it as off for the swapchain.
	 */
	@JvmStatic
	fun fgLocksVsync(): Boolean =
		ClientRuntime.active().activeControls()?.frameGenerationEnabled == true
}
