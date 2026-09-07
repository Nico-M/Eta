package fuck.andes.agent.voice

import fuck.andes.agent.media.AgentModelImageEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaAssistInvocationReducerTest {
    private val reducer = EtaAssistInvocationReducer

    @Test
    fun `structure then screenshot publishes immediately`() {
        val waiting = reducer.begin("entry-1")
        val (mid, first) = reducer.onCallback(
            waiting, "entry-1", EtaAssistInvocationReducer.Kind.STRUCTURE, nowElapsed = 100L,
        )
        assertEquals(EtaAssistInvocationReducer.Action.SchedulePartial, first)
        val (end, second) = reducer.onCallback(
            mid, "entry-1", EtaAssistInvocationReducer.Kind.SCREENSHOT, nowElapsed = 150L,
        )
        assertEquals(EtaAssistInvocationReducer.Action.Publish, second)
        assertEquals(EtaAssistInvocationReducer.InvocationState.Terminal, end)
    }

    @Test
    fun `screenshot then structure publishes immediately`() {
        val waiting = reducer.begin("entry-1")
        val (mid, first) = reducer.onCallback(
            waiting, "entry-1", EtaAssistInvocationReducer.Kind.SCREENSHOT, nowElapsed = 100L,
        )
        assertEquals(EtaAssistInvocationReducer.Action.SchedulePartial, first)
        val (end, second) = reducer.onCallback(
            mid, "entry-1", EtaAssistInvocationReducer.Kind.STRUCTURE, nowElapsed = 150L,
        )
        assertEquals(EtaAssistInvocationReducer.Action.Publish, second)
        assertEquals(EtaAssistInvocationReducer.InvocationState.Terminal, end)
    }

    @Test
    fun `single callback schedules partial and timeout publishes`() {
        val waiting = reducer.begin("entry-1")
        val (mid, action) = reducer.onCallback(
            waiting, "entry-1", EtaAssistInvocationReducer.Kind.STRUCTURE, nowElapsed = 100L,
        )
        assertEquals(EtaAssistInvocationReducer.Action.SchedulePartial, action)
        val (end, timeout) = reducer.onPartialTimeout(mid, "entry-1", nowElapsed = 400L)
        assertEquals(EtaAssistInvocationReducer.Action.Publish, timeout)
        assertEquals(EtaAssistInvocationReducer.InvocationState.Terminal, end)
    }

    @Test
    fun `mismatched entry id is late or stale`() {
        val waiting = reducer.begin("entry-1")
        val (next, action) = reducer.onCallback(
            waiting, "entry-other", EtaAssistInvocationReducer.Kind.STRUCTURE, nowElapsed = 100L,
        )
        assertEquals(EtaAssistInvocationReducer.Action.LateOrStale(reducer.ERROR_LATE_OR_STALE), action)
        assertEquals(waiting, next)
    }

    @Test
    fun `terminal state rejects all callbacks`() {
        val waiting = reducer.begin("entry-1")
        val (mid, _) = reducer.onCallback(
            waiting, "entry-1", EtaAssistInvocationReducer.Kind.STRUCTURE, nowElapsed = 100L,
        )
        val (terminal, _) = reducer.onCallback(
            mid, "entry-1", EtaAssistInvocationReducer.Kind.SCREENSHOT, nowElapsed = 150L,
        )
        val (after, action) = reducer.onCallback(
            terminal, "entry-1", EtaAssistInvocationReducer.Kind.STRUCTURE, nowElapsed = 200L,
        )
        assertEquals(EtaAssistInvocationReducer.Action.LateOrStale(reducer.ERROR_LATE_OR_STALE), action)
        assertEquals(EtaAssistInvocationReducer.InvocationState.Terminal, after)
    }

    @Test
    fun `begin does not start the timer`() {
        val waiting = reducer.begin("entry-1") as EtaAssistInvocationReducer.InvocationState.Waiting
        assertNull(waiting.firstCallbackElapsed)
        assertFalse(waiting.hasStructureCallback)
        assertFalse(waiting.hasScreenshotCallback)
    }
}
