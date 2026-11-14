package me.snowmii.streamline;

import java.nio.file.Path;

public final class NativeTestAccess {
	private NativeTestAccess() {}

	public static Native open(Path library) {
		return Native.open(library);
	}
}
