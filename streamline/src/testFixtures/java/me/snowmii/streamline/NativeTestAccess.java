package me.snowmii.streamline;

import java.nio.file.Path;

/** Test-fixture access to the package-private native implementation factory. */
public final class NativeTestAccess {
	private NativeTestAccess() {}

	public static Native open(Path library) {
		return NativeTestAccess.open(library);
	}
}
