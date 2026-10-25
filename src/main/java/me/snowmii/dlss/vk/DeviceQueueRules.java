package me.snowmii.dlss.vk;

import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import me.snowmii.streamline.SlQueueRequirements;

/** Reserves distinct Streamline queue ranges after the host's queues in each family. */
public final class DeviceQueueRules {
	private DeviceQueueRules() {}

	public record Layout(Int2IntMap counts, int graphicsStart, int computeStart) {}

	public static Layout reserve(
		final Int2IntMap hostCounts, final int graphicsFamily, final int computeFamily,
		final SlQueueRequirements requirements
	) {
		final Int2IntMap counts = new Int2IntArrayMap(hostCounts);
		final int graphicsStart = counts.get(graphicsFamily);
		if (requirements != null) {
			counts.put(graphicsFamily, graphicsStart + requirements.graphicsQueues());
		}
		// A shared graphics/compute family must not give both consumers the same range.
		final int computeStart = computeFamily < 0 ? 0 : counts.get(computeFamily);
		if (requirements != null && computeFamily >= 0) {
			counts.put(computeFamily, computeStart + requirements.computeQueues());
		}
		return new Layout(counts, graphicsStart, computeStart);
	}
}
