package me.snowmii.streamline;

import java.util.Objects;

/**
 * One present-marker event as the native log recorded it: the marker type and the Streamline
 * frame index (the retained frame token) the marker was emitted under.
 */
public record PresentMarkerEvent(PresentMarkerType type, int frameIndex) {
	public PresentMarkerEvent {
		Objects.requireNonNull(type, "type");
	}
}