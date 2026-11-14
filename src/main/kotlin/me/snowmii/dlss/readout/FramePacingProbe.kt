package me.snowmii.dlss.readout

class FramePacingProbe {
	enum class Span {
		FG_INPUT_WAIT,
		FG_STATUS_POLL,
		REFLEX_SLEEP,
		SWAPCHAIN_ACQUIRE,
		RENDER_SUBMIT,
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

	fun sampleAndReset(): String? = format()?.also { reset() }

	fun peek(): String? = format()

	private fun format(): String? {
		if (intervalSamples == 0) {
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
