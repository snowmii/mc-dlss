package me.snowmii.streamline;

/**
 * The stored FG multiplier and the device ceiling, read through
 * {@code mc_dlss_query_fg_multiplier}.
 *
 * <p>{@code current} is the {@code numFramesToGenerate} the recorded DLSS-G options carry
 * ({@code 1} is 2x, {@code 2} is 3x, and so on); {@code max} is the device's
 * {@code DLSSGState::numFramesToGenerateMax} read fresh from {@code slDLSSGGetState} - the
 * upper bound a multiplier cycle wraps against, so an unsupported multiplier is never offered.
 */
public record FgMultiplier(
	/** The {@code numFramesToGenerate} the recorded DLSS-G options carry. */
	int current,
	/** The device's {@code numFramesToGenerateMax}: the cycle's ceiling and wrap point. */
	int max
) {}