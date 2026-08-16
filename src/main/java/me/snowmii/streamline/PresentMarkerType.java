package me.snowmii.streamline;

/**
 * The two Reflex present markers this module emits at the present handoff, as the native
 * event log tags each actually-emitted marker with.
 */
public enum PresentMarkerType {
	PRESENT_START(0),
	PRESENT_END(1);

	private final int nativeValue;

	PresentMarkerType(final int nativeValue) {
		this.nativeValue = nativeValue;
	}

	/** The raw value the native event log stores this marker type under. */
	public int nativeValue() {
		return nativeValue;
	}

	/** Resolves the native value back to the marker type. */
	public static PresentMarkerType fromNative(final int value) {
		for (PresentMarkerType type : values()) {
			if (type.nativeValue == value) {
				return type;
			}
		}
		throw new IllegalArgumentException("unknown present marker type " + value);
	}
}