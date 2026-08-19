package me.snowmii.dlss.readout

/**
 * Render-thread wall-time spans for the calls an app frame can stall in.
 *
 * [Span.QUEUE_PRESENT] is also the frame clock: its end is last, so the gap between consecutive
 * ends is the app-frame interval DLSS-G subdivides. Mean is rate; max/mean >> 1 is stutter.
 *
 * Render thread only, allocation-free per frame. A span whose start was never recorded
 * contributes nothing, so FG-only seams report over the frames they ran on.
 */
class FramePacingProbe {
	enum class Span {
		/** `waitFgInputsIdle`: the DLSS-G input-processing completion fence, FG-active frames only. */
		FG_INPUT_WAIT,

		/** `queryFgState`: the per-frame `slDLSSGGetState` status read. */
		FG_STATUS_POLL,

		/**
		 * `reflexInputSample`: the input marker, which runs `slReflexSleep` before it emits. This
		 * is where Reflex enforces `frameLimitUs` and where the DLSS-G pacer holds the app back,
		 * so it is the one blocking call left in the frame outside the render seams.
		 */
		REFLEX_SLEEP,

		/** `VulkanGpuSurface.acquireNextTexture`: blocks while the pacer holds every image. */
		SWAPCHAIN_ACQUIRE,

		/**
		 * `CommandEncoder.submit()` at the tail of `renderFrame`: where the frame's work reaches
		 * the queue DLSS-G blocks while it interpolates.
		 */
		RENDER_SUBMIT,

		/** `VulkanGpuSurface.present`: the interposed `vkQueuePresentKHR`, and the frame clock. */
		QUEUE_PRESENT,
	}

	private val started = LongArray(Span.entries.size)
	private val total = LongArray(Span.entries.size)
	private val maximum = LongArray(Span.entries.size)
	private val samples = IntArray(Span.entries.size)

	private var lastPresentEnd = 0L
	private var intervalTotal = 0L
	private var intervalMaximum = 0L
	private var intervalSamples = 0

	fun begin(span: Span) {
		started[span.ordinal] = System.nanoTime()
	}

	fun end(span: Span) {
		val start = started[span.ordinal]
		if (start == 0L) {
			// No begin for this end: the seam fired outside a measured frame, or the frame threw
			// between the two. Either way there is no duration, and inventing one from a stale
			// start would report the gap between frames as the call's cost.
			return
		}
		val now = System.nanoTime()
		started[span.ordinal] = 0L
		record(span.ordinal, now - start)
		if (span == Span.QUEUE_PRESENT) {
			recordInterval(now)
		}
	}

	private fun record(index: Int, duration: Long) {
		total[index] += duration
		samples[index]++
		if (duration > maximum[index]) {
			maximum[index] = duration
		}
	}

	private fun recordInterval(presentEnd: Long) {
		if (lastPresentEnd != 0L) {
			val interval = presentEnd - lastPresentEnd
			intervalTotal += interval
			intervalSamples++
			if (interval > intervalMaximum) {
				intervalMaximum = interval
			}
		}
		lastPresentEnd = presentEnd
	}

	/**
	 * The window's line - `mean/max` milliseconds per span, frame interval first - and a reset.
	 *
	 * Null when no frame completed a present in the window, which is a session that is not
	 * presenting through the measured seams at all rather than one with nothing to report.
	 */
	fun sampleAndReset(): String? {
		if (intervalSamples == 0) {
			reset()
			return null
		}
		val line = StringBuilder(", pacing=frame ")
		line.append(millis(intervalTotal / intervalSamples)).append('/').append(millis(intervalMaximum))
		for (span in Span.entries) {
			val count = samples[span.ordinal]
			if (count == 0) {
				continue
			}
			line.append(' ').append(name(span)).append(' ')
				.append(millis(total[span.ordinal] / count)).append('/').append(millis(maximum[span.ordinal]))
				.append(" x").append(count)
		}
		reset()
		return line.toString()
	}

	private fun reset() {
		total.fill(0L)
		maximum.fill(0L)
		samples.fill(0)
		intervalTotal = 0L
		intervalMaximum = 0L
		intervalSamples = 0
	}

	private companion object {
		fun millis(nanos: Long): String = "%.2f".format(nanos / 1_000_000.0)

		fun name(span: Span): String = when (span) {
			Span.FG_INPUT_WAIT -> "fgwait"
			Span.FG_STATUS_POLL -> "fgpoll"
			Span.REFLEX_SLEEP -> "sleep"
			Span.SWAPCHAIN_ACQUIRE -> "acquire"
			Span.RENDER_SUBMIT -> "submit"
			Span.QUEUE_PRESENT -> "present"
		}
	}
}
