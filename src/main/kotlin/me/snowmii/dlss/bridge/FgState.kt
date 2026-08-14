package me.snowmii.dlss.bridge

/**
 * One snapshot of Streamline's live DLSS-G state, read through `slDLSSGGetState` by
 * `mc_dlss_query_fg_state`.
 *
 * The present-generation proof reads this to observe the interposed `vkQueuePresentKHR` path
 * working: [status] is the raw `DLSSGStatus` word (zero is `eDLSSGStatusOk`, every failure
 * is its own bit), [numFramesPresented] is the number of actual presentations per app frame
 * (`2` means one real plus one generated frame), and [lastPresentInputsProcessingFenceValue] is the
 * value the plugin's input-processing completion timeline semaphore last reached - the value
 * `waitFgInputsIdle` waits on, read from the same query, so the two always travel together.
 * [inputsProcessingCompletionFence] is the semaphore handle itself.
 */
data class FgState(
	/** The raw `sl::DLSSGStatus` word; `eDLSSGStatusOk` is zero. */
	val status: Int,
	/** Actual presentations per app frame; `2` is 2x frame generation. */
	val numFramesPresented: Int,
	/** The value the input-processing completion fence last reached for presented frames. */
	val lastPresentInputsProcessingFenceValue: Long,
	/** The Vulkan timeline semaphore the plugin signals input processing with. */
	val inputsProcessingCompletionFence: Long,
)
