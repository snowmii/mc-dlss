package me.snowmii.dlss;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.Objects;

/** Flat Java 25 FFM binding. NGX types and ownership stay inside mc_dlss_native. */
public final class DlssNative implements AutoCloseable, DlssNativeApi {
	private static final int SUCCESS = 1;
	private static final ValueLayout.OfInt JAVA_INT = ValueLayout.JAVA_INT;
	private static final ValueLayout.OfLong JAVA_LONG = ValueLayout.JAVA_LONG;
	private static final ValueLayout.OfFloat JAVA_FLOAT = ValueLayout.JAVA_FLOAT;
	private static final Linker LINKER = Linker.nativeLinker();

	private final Arena arena;
	private final MethodHandle initialize;
	private final MethodHandle queryOptimalDimensions;
	private final MethodHandle configure;
	private final MethodHandle evaluate;
	private final MethodHandle reset;
	private final MethodHandle close;
	private boolean closed;

	private DlssNative(final Arena arena, final SymbolLookup lookup) {
		this.arena = arena;
		this.initialize = bind(
			lookup,
			"mc_dlss_initialize",
			FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
		);
		this.queryOptimalDimensions = bind(
			lookup,
			"mc_dlss_query_optimal_dimensions",
			FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
		);
		this.configure = bind(
			lookup,
			"mc_dlss_configure",
			FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)
		);
		this.evaluate = bind(
			lookup,
			"mc_dlss_evaluate",
			FunctionDescriptor.of(
				JAVA_INT,
				JAVA_LONG,
				JAVA_LONG,
				JAVA_LONG,
				JAVA_LONG,
				JAVA_LONG,
				JAVA_INT,
				JAVA_INT,
				JAVA_INT,
				JAVA_INT,
				JAVA_FLOAT,
				JAVA_FLOAT,
				JAVA_FLOAT,
				JAVA_FLOAT,
				JAVA_FLOAT,
				JAVA_INT
			)
		);
		this.reset = bind(lookup, "mc_dlss_reset", FunctionDescriptor.of(JAVA_INT));
		this.close = bind(lookup, "mc_dlss_close", FunctionDescriptor.of(JAVA_INT));
	}

	public static DlssNative open(final Path libraryPath) {
		Objects.requireNonNull(libraryPath, "libraryPath");
		final Arena arena = Arena.ofShared();
		try {
			return new DlssNative(arena, SymbolLookup.libraryLookup(libraryPath, arena));
		} catch (Throwable error) {
			arena.close();
			throw new DlssNativeException("load-library", error);
		}
	}

	@Override
	public int initialize(
		final long vkInstance,
		final long vkPhysicalDevice,
		final long vkDevice,
		final Path sdkPath,
		final Path dataPath
	) {
		try (Arena callArena = Arena.ofConfined()) {
			final MemorySegment sdk = callArena.allocateFrom(sdkPath.toString());
			final MemorySegment data = callArena.allocateFrom(dataPath.toString());
			return (int)this.initialize.invokeExact(vkInstance, vkPhysicalDevice, vkDevice, sdk, data);
		} catch (Throwable error) {
			throw nativeError("initialize", error);
		}
	}

	@Override
	public DlssDimensions queryOptimalDimensions(
		final int outputWidth,
		final int outputHeight,
		final int qualityMode
	) {
		try (Arena callArena = Arena.ofConfined()) {
			final MemorySegment renderWidth = callArena.allocate(JAVA_INT);
			final MemorySegment renderHeight = callArena.allocate(JAVA_INT);
			final int result = (int)this.queryOptimalDimensions.invokeExact(
				outputWidth,
				outputHeight,
				qualityMode,
				renderWidth,
				renderHeight
			);
			if (result != SUCCESS) {
				throw new DlssNativeException("query-dimensions", result);
			}
			return new DlssDimensions(renderWidth.get(JAVA_INT, 0), renderHeight.get(JAVA_INT, 0));
		} catch (DlssNativeException error) {
			throw error;
		} catch (Throwable error) {
			throw nativeError("query-dimensions", error);
		}
	}

	@Override
	public int configure(
		final int outputWidth,
		final int outputHeight,
		final int renderWidth,
		final int renderHeight,
		final int qualityMode
	) {
		try {
			return (int)this.configure.invokeExact(outputWidth, outputHeight, renderWidth, renderHeight, qualityMode);
		} catch (Throwable error) {
			throw nativeError("configure", error);
		}
	}

	@Override
	public int evaluate(
		final long commandBuffer,
		final long colorView,
		final long depthView,
		final long motionView,
		final long outputView,
		final int renderWidth,
		final int renderHeight,
		final int outputWidth,
		final int outputHeight,
		final float jitterX,
		final float jitterY,
		final float motionScaleX,
		final float motionScaleY,
		final float frameTimeMilliseconds,
		final boolean resetHistory
	) {
		try {
			return (int)this.evaluate.invokeExact(
				commandBuffer,
				colorView,
				depthView,
				motionView,
				outputView,
				renderWidth,
				renderHeight,
				outputWidth,
				outputHeight,
				jitterX,
				jitterY,
				motionScaleX,
				motionScaleY,
				frameTimeMilliseconds,
				resetHistory ? 1 : 0
			);
		} catch (Throwable error) {
			throw nativeError("evaluate", error);
		}
	}

	public int reset() {
		try {
			return (int)this.reset.invokeExact();
		} catch (Throwable error) {
			throw nativeError("reset", error);
		}
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}

		this.closed = true;
		try {
			final int result = (int)this.close.invokeExact();
			if (result != SUCCESS) {
				throw new DlssNativeException("close", result);
			}
		} catch (DlssNativeException error) {
			throw error;
		} catch (Throwable error) {
			throw nativeError("close", error);
		} finally {
			this.arena.close();
		}
	}

	private static MethodHandle bind(
		final SymbolLookup lookup,
		final String symbol,
		final FunctionDescriptor descriptor
	) {
		return LINKER.downcallHandle(
			lookup.find(symbol).orElseThrow(() -> new IllegalStateException("Missing native symbol: " + symbol)),
			descriptor
		);
	}

	private static DlssNativeException nativeError(final String stage, final Throwable error) {
		if (error instanceof DlssNativeException nativeError) {
			return nativeError;
		}
		return new DlssNativeException(stage, error);
	}
}
