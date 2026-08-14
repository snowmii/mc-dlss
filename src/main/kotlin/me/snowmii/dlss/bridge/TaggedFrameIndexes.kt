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

/**
 * The two Reflex present markers this module emits at the present handoff, as the native
 * event log tags each actually-emitted marker with.
 */
enum class PresentMarkerType(val nativeValue: Int) {
	PRESENT_START(0),
	PRESENT_END(1),
	;

	companion object {
		@JvmStatic
		fun fromNative(value: Int): PresentMarkerType =
			entries.firstOrNull { it.nativeValue == value }
				?: throw IllegalArgumentException("unknown present marker type $value")
	}
}

/**
 * One present-marker event as the native log recorded it: the marker type and the Streamline
 * frame index (the retained frame token) the marker was emitted under.
 */
data class PresentMarkerEvent(
	val type: PresentMarkerType,
	val frameIndex: Int,
)

/**
 * The present-marker oracle: how many PRESENT_START and PRESENT_END markers the module has
 * actually emitted (per-type cumulative counts), the total event count, and the recent
 * event log in emission order, as reported by `mc_dlss_query_present_markers`.
 *
 * Each event's frame index must equal the frame indexes the frame's SR/FG tags (and its
 * common constants) recorded under: the handoff emits both markers against the same retained
 * frame token the tags and the constants used, so equality of the events' indexes with
 * [TaggedFrameIndexes] is what proves the present bracket correlates with the frame DLSS-G
 * generates. The per-type counts must each advance by exactly one per successful handoff and
 * stay unchanged across refused or pre-ready handoffs, which is what proves the "exactly one
 * PRESENT_START then PRESENT_END" half of the present-marker invariant: the START and END
 * events are recorded separately and in emission order, so a handoff whose END marker failed
 * reads as one START event and no END rather than as a pair that never happened.
 */
data class PresentMarkerEvents(
	/** How many PRESENT_START markers the module has actually emitted. */
	val startCount: Int,
	/** How many PRESENT_END markers the module has actually emitted. */
	val endCount: Int,
	/** How many marker events the module has actually emitted in total (start + end). */
	val eventCount: Int,
	/** The most recent events in emission order, at most [LOG_CAPACITY] of them. */
	val events: List<PresentMarkerEvent>,
) {
	companion object {
		/**
		 * The number of events the native log ring retains; the oracle never returns more
		 * events than this, while the counts keep answering the whole session.
		 */
		const val LOG_CAPACITY = 16
	}
}
