package me.snowmii.dlss;

import java.util.Locale;

public final class DlssNativeException extends RuntimeException {
	private final String stage;
	private final int resultCode;

	public DlssNativeException(final String stage, final int resultCode, final Throwable cause) {
		super("DLSS native " + stage + " failed with result 0x" + String.format(Locale.ROOT, "%08X", resultCode), cause);
		this.stage = stage;
		this.resultCode = resultCode;
	}

	public DlssNativeException(final String stage, final int resultCode) {
		this(stage, resultCode, null);
	}

	public DlssNativeException(final String stage, final Throwable cause) {
		this(stage, 0, cause);
	}

	public String stage() {
		return this.stage;
	}

	public int resultCode() {
		return this.resultCode;
	}
}
