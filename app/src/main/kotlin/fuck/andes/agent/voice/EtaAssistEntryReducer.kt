package fuck.andes.agent.voice

/**
 * Overlay 侧单 entry 的终态 reducer，驱动 consume、fallback 与取消。
 *
 * 无 Android 依赖。terminal 状态不可逆：`AssistConsumed`/`FallbackStarted`/`Cancelled`
 * 都不能回到 [State.Waiting]；`FallbackStarted` 收到 ready 只产生 [Action.Discard]。
 */
internal object EtaAssistEntryReducer {
    sealed interface State {
        data class Waiting(
            val entryId: String,
            val generation: Long,
            val deadlineElapsed: Long,
        ) : State

        data object AssistConsumed : State
        data object FallbackStarted : State
        data object Cancelled : State
    }

    sealed interface Event {
        data object ConsumeSucceeded : Event
        data object ReadyMissingOrInvalid : Event
        data object Deadline : Event
        data object Dismiss : Event
        data object LateReady : Event
    }

    sealed interface Action {
        data object PresentAssist : Action
        data object StartFallback : Action
        data object Discard : Action
        data object None : Action
    }

    fun waiting(entryId: String, generation: Long, deadlineElapsed: Long): State =
        State.Waiting(entryId = entryId, generation = generation, deadlineElapsed = deadlineElapsed)

    fun reduce(state: State, event: Event): Pair<State, Action> = when (state) {
        is State.Waiting -> when (event) {
            Event.ConsumeSucceeded -> State.AssistConsumed to Action.PresentAssist
            Event.ReadyMissingOrInvalid -> state to Action.None
            Event.Deadline -> State.FallbackStarted to Action.StartFallback
            Event.Dismiss -> State.Cancelled to Action.Discard
            Event.LateReady -> state to Action.None
        }

        State.AssistConsumed -> when (event) {
            Event.ConsumeSucceeded -> state to Action.None
            Event.ReadyMissingOrInvalid -> state to Action.None
            Event.Deadline -> state to Action.None
            Event.Dismiss -> State.Cancelled to Action.None
            Event.LateReady -> state to Action.Discard
        }

        State.FallbackStarted -> when (event) {
            Event.ConsumeSucceeded -> state to Action.None
            Event.ReadyMissingOrInvalid -> state to Action.None
            Event.Deadline -> state to Action.None
            Event.Dismiss -> State.Cancelled to Action.None
            Event.LateReady -> state to Action.Discard
        }

        State.Cancelled -> when (event) {
            else -> state to Action.Discard
        }
    }
}
