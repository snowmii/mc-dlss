package me.snowmii.streamline;

/**
 * The Streamline frame indices the last {@code tagSrResources} and {@code tagFgResources}
 * calls tagged under, as the runtime numbered them through {@code slGetNewFrameToken}.
 *
 * <p>One frame's SR and FG tags must land under the same index: the FG tag reuses the frame
 * token the SR tag obtained and retained rather than calling {@code slGetNewFrameToken} again,
 * and equality of this pair is the behavior-level oracle the composed rung asserts. A tag that
 * advanced the frame instead would record a strictly later index under its slot.
 */
public record TaggedFrameIndexes(
	/** The frame index the last {@code mc_dlss_tag_sr_resources} call tagged under. */
	int srFrameIndex,
	/** The frame index the last {@code mc_dlss_tag_fg_resources} call tagged under. */
	int fgFrameIndex
) {}