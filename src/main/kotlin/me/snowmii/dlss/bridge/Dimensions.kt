package me.snowmii.dlss.bridge

/**
 * A pixel size, in the units the flat native ABI takes them.
 *
 * Lives in the bridge package because the ABI is what constrains it: the native side is told an
 * output size and answers with a render size, and every layer above - the session's
 * configuration, the router's per-frame decision, the scene target's allocation - is describing
 * one of those two numbers. Defining it here is what lets [NativeApi] speak in it without the
 * lowest layer of the mod having to import the higher ones.
 */
data class DlssDimensions(
	val width: Int,
	val height: Int,
) {
	init {
		require(width > 0) { "width must be positive" }
		require(height > 0) { "height must be positive" }
	}

	override fun toString(): String = "${width}x$height"
}
