package me.snowmii.dlss.vk;

/** Monotonic completion point from one {@link VulkanContext}. */
public record GpuCompletionToken(long contextId, long value) implements Comparable<GpuCompletionToken> {
	public GpuCompletionToken {
		if (contextId <= 0L) throw new IllegalArgumentException("contextId must be positive");
		if (value < 0L) throw new IllegalArgumentException("value must not be negative");
	}

	@Override
	public int compareTo(final GpuCompletionToken other) {
		if (contextId != other.contextId) {
			throw new IllegalArgumentException("Cannot compare completion tokens from different Vulkan contexts");
		}
		return Long.compare(value, other.value);
	}
}
