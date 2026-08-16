package me.snowmii.streamline;

import java.util.List;
import java.util.Objects;

/**
 * The present-marker oracle: how many PRESENT_START and PRESENT_END markers the module has
 * actually emitted (per-type cumulative counts), the total event count, and the recent event
 * log in emission order, as reported by {@code mc_dlss_query_present_markers}.
 *
 * <p>Each event's frame index must equal the frame indexes the frame's SR/FG tags (and its
 * common constants) recorded under: the handoff emits both markers against the same retained
 * frame token the tags and the constants used, so equality of the events' indexes with
 * {@link TaggedFrameIndexes} is what proves the present bracket correlates with the frame
 * DLSS-G generates. The per-type counts must each advance by exactly one per successful
 * handoff and stay unchanged across refused or pre-ready handoffs, which is what proves the
 * "exactly one PRESENT_START then PRESENT_END" half of the present-marker invariant: the START
 * and END events are recorded separately and in emission order, so a handoff whose END marker
 * failed reads as one START event and no END rather than as a pair that never happened.
 */
public record PresentMarkerEvents(
	/** How many PRESENT_START markers the module has actually emitted. */
	int startCount,
	/** How many PRESENT_END markers the module has actually emitted. */
	int endCount,
	/** How many marker events the module has actually emitted in total (start + end). */
	int eventCount,
	/** The most recent events in emission order, at most {@link #LOG_CAPACITY} of them. */
	List<PresentMarkerEvent> events
) {
	/**
	 * The number of events the native log ring retains; the oracle never returns more
	 * events than this, while the counts keep answering the whole session.
	 */
	public static final int LOG_CAPACITY = 16;

	public PresentMarkerEvents {
		Objects.requireNonNull(events, "events");
	}
}