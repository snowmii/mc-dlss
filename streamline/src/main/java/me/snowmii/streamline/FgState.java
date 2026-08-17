package me.snowmii.streamline;

/**
 * One snapshot of Streamline's live DLSS-G state, read through {@code slDLSSGGetState} by
 * {@code mc_dlss_query_fg_state}.
 *
 * <p>The present-generation integration reads this to observe the interposed
 * {@code vkQueuePresentKHR} path working: {@code status} is the raw {@code DLSSGStatus} word
 * (zero is {@code eDLSSGStatusOk}, every failure is its own bit), {@code numFramesPresented}
 * is the number of actual presentations per app frame ({@code 2} means one real plus one
 * generated frame), and {@code lastPresentInputsProcessingFenceValue} is the value the
 * plugin's input-processing completion timeline semaphore last reached - the value
 * {@code waitFgInputsIdle} waits on, read from the same query, so the two always travel
 * together. {@code inputsProcessingCompletionFence} is the semaphore handle itself.
 */
public record FgState(
	/** The raw {@code sl::DLSSGStatus} word; {@code eDLSSGStatusOk} is zero. */
	int status,
	/** Actual presentations per app frame; {@code 2} is 2x frame generation. */
	int numFramesPresented,
	/** The value the input-processing completion fence last reached for presented frames. */
	long lastPresentInputsProcessingFenceValue,
	/** The Vulkan timeline semaphore the plugin signals input processing with. */
	long inputsProcessingCompletionFence
) {}