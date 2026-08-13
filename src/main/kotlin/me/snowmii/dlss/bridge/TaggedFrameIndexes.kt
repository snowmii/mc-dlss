package me.snowmii.dlss.bridge

/**
 * The Streamline frame indices the last `tagSrResources` and `tagFgResources` calls tagged
 * under, as the runtime numbered them through `slGetNewFrameToken`.
 *
 * One frame's SR and FG tags must land under the same index: the FG tag reuses the frame token
 * the SR tag obtained and retained rather than calling `slGetNewFrameToken` again, and equality
 * of this pair is the behavior-level oracle the composed rung asserts. A tag that advanced the
 * frame instead would record a strictly later index under its slot.
 */
data class TaggedFrameIndexes(
	/** The frame index the last `mc_dlss_tag_sr_resources` call tagged under. */
	val srFrameIndex: Int,
	/** The frame index the last `mc_dlss_tag_fg_resources` call tagged under. */
	val fgFrameIndex: Int,
)
