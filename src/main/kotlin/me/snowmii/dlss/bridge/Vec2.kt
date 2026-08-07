package me.snowmii.dlss.bridge

/**
 * A two-component float pair, mirroring the ABI's `McDlssVec2`.
 *
 * Both values the evaluation carries in this shape - the sub-pixel jitter offset and the
 * motion-vector scale - are a pair whose halves are meaningless apart, and both used to cross
 * as two adjacent `float` arguments where nothing but position distinguished x from y.
 */
data class Vec2(
	val x: Float,
	val y: Float,
)
