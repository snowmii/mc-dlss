package me.snowmii.dlss.vk;

import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import me.snowmii.streamline.SlQueueRequirements;

public final class DeviceQueueRules {
	private DeviceQueueRules() {}

	public record Layout(Int2IntMap counts, int graphicsStart, int computeStart) {}

	public static Layout reserve(
		final Int2IntMap hostCounts, final int graphicsFamily, final int computeFamily,
		final SlQueueRequirements requirements
	) {
		final Int2IntMap counts = new Int2IntArrayMap(hostCounts);
		final int graphicsStart = allocateRange(counts, graphicsFamily, requirements == null ? 0 : requirements.graphicsQueues());
		final int computeStart = computeFamily < 0
			? 0
			: allocateRange(counts, computeFamily, requirements == null ? 0 : requirements.computeQueues());
		return new Layout(counts, graphicsStart, computeStart);
	}

	private static int allocateRange(final Int2IntMap counts, final int family, final int queueCount) {
		final int start = counts.get(family);
		if (queueCount > 0) {
			counts.put(family, start + queueCount);
		}
		return start;
	}
}
