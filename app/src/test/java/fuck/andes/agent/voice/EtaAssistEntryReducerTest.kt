package fuck.andes.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class EtaAssistEntryReducerTest {
    private val reducer = EtaAssistEntryReducer

    @Test
    fun `consume success transitions to assist consumed`() {
        val waiting = reducer.waiting("entry-1", 1L, deadlineElapsed = 1_000L)
        val (state, action) = reducer.reduce(waiting, EtaAssistEntryReducer.Event.ConsumeSucceeded)
        assertEquals(EtaAssistEntryReducer.State.AssistConsumed, state)
        assertEquals(EtaAssistEntryReducer.Action.PresentAssist, action)
    }

    @Test
    fun `ready missing or invalid stays waiting`() {
        val waiting = reducer.waiting("entry-1", 1L, deadlineElapsed = 1_000L)
        val (state, action) = reducer.reduce(waiting, EtaAssistEntryReducer.Event.ReadyMissingOrInvalid)
        assertEquals(waiting, state)
        assertEquals(EtaAssistEntryReducer.Action.None, action)
    }

    @Test
    fun `deadline transitions to fallback`() {
        val waiting = reducer.waiting("entry-1", 1L, deadlineElapsed = 1_000L)
        val (state, action) = reducer.reduce(waiting, EtaAssistEntryReducer.Event.Deadline)
        assertEquals(EtaAssistEntryReducer.State.FallbackStarted, state)
        assertEquals(EtaAssistEntryReducer.Action.StartFallback, action)
    }

    @Test
    fun `dismiss from waiting cancels with discard`() {
        val waiting = reducer.waiting("entry-1", 1L, deadlineElapsed = 1_000L)
        val (state, action) = reducer.reduce(waiting, EtaAssistEntryReducer.Event.Dismiss)
        assertEquals(EtaAssistEntryReducer.State.Cancelled, state)
        assertEquals(EtaAssistEntryReducer.Action.Discard, action)
    }

    @Test
    fun `terminal states never return to waiting`() {
        val consumed = EtaAssistEntryReducer.State.AssistConsumed
        val (afterDeadline, _) = reducer.reduce(consumed, EtaAssistEntryReducer.Event.Deadline)
        assertEquals(EtaAssistEntryReducer.State.AssistConsumed, afterDeadline)

        val fallback = EtaAssistEntryReducer.State.FallbackStarted
        val (afterConsume, _) = reducer.reduce(fallback, EtaAssistEntryReducer.Event.ConsumeSucceeded)
        assertEquals(EtaAssistEntryReducer.State.FallbackStarted, afterConsume)
    }

    @Test
    fun `late ready on fallback only discards`() {
        val fallback = EtaAssistEntryReducer.State.FallbackStarted
        val (state, action) = reducer.reduce(fallback, EtaAssistEntryReducer.Event.LateReady)
        assertEquals(EtaAssistEntryReducer.State.FallbackStarted, state)
        assertEquals(EtaAssistEntryReducer.Action.Discard, action)
    }

    @Test
    fun `late ready on consumed only discards`() {
        val consumed = EtaAssistEntryReducer.State.AssistConsumed
        val (state, action) = reducer.reduce(consumed, EtaAssistEntryReducer.Event.LateReady)
        assertEquals(EtaAssistEntryReducer.State.AssistConsumed, state)
        assertEquals(EtaAssistEntryReducer.Action.Discard, action)
    }

    @Test
    fun `cancelled state discards all events`() {
        val cancelled = EtaAssistEntryReducer.State.Cancelled
        val (state, action) = reducer.reduce(cancelled, EtaAssistEntryReducer.Event.ConsumeSucceeded)
        assertEquals(EtaAssistEntryReducer.State.Cancelled, state)
        assertEquals(EtaAssistEntryReducer.Action.Discard, action)
    }
}
