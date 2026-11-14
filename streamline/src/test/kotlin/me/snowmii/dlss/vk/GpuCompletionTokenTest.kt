package me.snowmii.dlss.vk

import me.snowmii.dlss.vk.VulkanContext
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GpuCompletionTokenTest {
    @Test
    fun completionAdvancesOnlyWhenEngineCallbackRuns() {
        val callbacks = ArrayList<Runnable>()
        val context = context(callbacks)

        val first = context.enqueueOnEngineEncoder(null)
        val second = context.enqueueOnEngineEncoder(null)

        assertTrue(first < second)
        assertFalse(context.isComplete(first))
        callbacks.removeFirst().run()
        assertTrue(context.isComplete(first))
        assertFalse(context.isComplete(second))
        callbacks.removeFirst().run()
        assertEquals(second, context.completedToken())
    }

    @Test
    fun deviceIdleCompletionFlushesTokenRetirements() {
        val context = context(ArrayList())
        val token = context.enqueueOnEngineEncoder(null)
        var retired = false
        context.retire(token) { retired = true }

        context.completeAfterDeviceIdle()

        assertTrue(retired)
        assertTrue(context.isComplete(token))
    }

    @Test
    fun rejectedEnqueueDoesNotIssueSubmissionToken() {
        val context = VulkanContext.fromNativeHandles(
            1L, 2L, 3L, 4L, 0, 0, 0, 0,
            { null },
            { error("enqueue rejected") },
            { },
        )

        assertFailsWith<IllegalStateException> { context.enqueueOnEngineEncoder(null) }
        assertEquals(0L, context.lastSubmittedToken().value())
    }

    @Test
    fun tokensFromDifferentContextsCannotMix() {
        val left = context(ArrayList()).lastSubmittedToken()
        val rightContext = context(ArrayList())
        val right = rightContext.lastSubmittedToken()

        assertFailsWith<IllegalArgumentException> { left.compareTo(right) }
        assertFailsWith<IllegalArgumentException> { rightContext.isComplete(left) }
    }

    private fun context(callbacks: MutableList<Runnable>): VulkanContext = VulkanContext.fromNativeHandles(
        1L, 2L, 3L, 4L, 0, 0, 0, 0,
        { null },
        { },
        { callbacks += it },
    )
}
