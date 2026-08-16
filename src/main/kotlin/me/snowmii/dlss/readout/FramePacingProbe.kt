package me.snowmii.dlss.readout

/**
 * Where an app frame's wall time actually goes, sampled on the render thread.
 *
 * Frame generation above 2x reviewed as stuttering with a *base* frame rate far below the cap in
 * force, and nothing the mod already reports can say why: the frame-rate line names the rate, the
 * DLSS-G suffix names the plugin's status and presented count, and neither says which call the
 * frame spent its milliseconds inside. The candidates each blame a different call - the DLSS-G
 * input-completion wait, the plugin's status read, a swapchain acquire that blocks because the
 * pacer is holding the images, or the interposed queue present itself - so the measurement is one
 * span per candidate, and the largest one is the answer.
 *
 * [Span.QUEUE_PRESENT] doubles as the frame clock: its end is the last thing an app frame does, so
 * the gap between consecutive ends is the app frame interval, which is the interval DLSS-G divides
 * into its sub-intervals. The mean says what the rate is; the maximum says whether it is even, and
 * an interval whose maximum is several times its mean is the stutter itself rather than a report
 * about it.
 *
 * Render thread only, and deliberately allocation-free per frame: a probe that costs a frame its
 * evenness cannot measure evenness. A span whose start was never recorded contributes nothing, so
 * a seam that only fires on some frames (the FG-active-only wait) reports over the frames it ran
 * on rather than diluting itself across the window.
 */
class FramePacingProbe {
	/** The four calls an app frame can stall inside, one span each. */
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
		 * `CommandEncoder.submit()` at the tail of `renderFrame`: where the frame's own work
		 * reaches the queue DLSS-G blocks while it interpolates. The first measurement found an
		 * app frame of 57ms with every other span at hundredths of a millisecond, so the stall
		 * was somewhere the probe did not look; this is the largest call in that gap.
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
