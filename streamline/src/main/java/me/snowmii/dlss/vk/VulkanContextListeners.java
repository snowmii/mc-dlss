package me.snowmii.dlss.vk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Callbacks invoked after the mod captures and registers a {@link VulkanContext}.
 */
public final class VulkanContextListeners {
	private static final List<Consumer<VulkanContext>> LISTENERS = new ArrayList<>();

	private VulkanContextListeners() {
	}

	public static synchronized void onRegistered(final Consumer<VulkanContext> listener) {
		Objects.requireNonNull(listener, "listener");
		LISTENERS.add(listener);
		final VulkanContext current = VulkanContextRegistry.getCurrent();
		if (current != null) {
			listener.accept(current);
		}
	}

	public static synchronized void dispatchRegistered(final VulkanContext context) {
		for (final Consumer<VulkanContext> listener : LISTENERS) {
			listener.accept(context);
		}
	}

	/** Test seam. */
	public static synchronized void clear() {
		LISTENERS.clear();
	}
}
