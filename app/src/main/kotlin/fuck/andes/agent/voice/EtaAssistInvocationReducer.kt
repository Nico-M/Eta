package fuck.andes.agent.voice

/**
 * Assist 回调的单次 invocation 终态 reducer。
 *
 * 专用单线程 scheduled executor 串行处理两个回调：两者齐全立即 publish，
 * 第一个有效回调触发 300 ms partial timer，deadline 仍只到一个则 publish 一次 partial；
 * publish 后 invocation 终态，迟到/旧 entryId/旧 generation 回调只记固定错误码，
 * 不再次 publish 也不写 payload。
 */
internal object EtaAssistInvocationReducer {
    const val ERROR_LATE_OR_STALE = "assist_invocation_late_or_stale"
    const val PARTIAL_WINDOW_MS = 300L

    sealed interface InvocationState {
        data object Idle : InvocationState
        data class Waiting(
            val entryId: String,
            val hasStructureCallback: Boolean,
            val hasScreenshotCallback: Boolean,
            val firstCallbackElapsed: Long?,
        ) : InvocationState
        data object Terminal : InvocationState
    }

    sealed interface Action {
        data object None : Action
        data object SchedulePartial : Action
        data object Publish : Action
        data class LateOrStale(val errorCode: String) : Action
    }

    fun begin(entryId: String): InvocationState =
        InvocationState.Waiting(
            entryId = entryId,
            hasStructureCallback = false,
            hasScreenshotCallback = false,
            firstCallbackElapsed = null,
        )

    /**
     * 首个有效回调返回 [Action.SchedulePartial]；第二种回调在 deadline 前返回
     * [Action.Publish]。entryId 不匹配或 Terminal 返回 [Action.LateOrStale]。
     */
    fun onCallback(
        state: InvocationState,
        callbackEntryId: String,
        kind: Kind,
        nowElapsed: Long,
    ): Pair<InvocationState, Action> {
        val waiting = state as? InvocationState.Waiting
            ?: return state to Action.LateOrStale(ERROR_LATE_OR_STALE)
        if (waiting.entryId != callbackEntryId) {
            return waiting to Action.LateOrStale(ERROR_LATE_OR_STALE)
        }
        val hasStructure = waiting.hasStructureCallback || kind == Kind.STRUCTURE
        val hasScreenshot = waiting.hasScreenshotCallback || kind == Kind.SCREENSHOT
        if (hasStructure && hasScreenshot) {
            return InvocationState.Terminal to Action.Publish
        }
        return waiting.copy(
            hasStructureCallback = hasStructure,
            hasScreenshotCallback = hasScreenshot,
            firstCallbackElapsed = waiting.firstCallbackElapsed ?: nowElapsed,
        ) to Action.SchedulePartial
    }

    /** deadline 到达且只有一种回调时 publish 一次 partial。 */
    fun onPartialTimeout(
        state: InvocationState,
        entryId: String,
        nowElapsed: Long,
    ): Pair<InvocationState, Action> {
        val waiting = state as? InvocationState.Waiting
            ?: return state to Action.LateOrStale(ERROR_LATE_OR_STALE)
        if (waiting.entryId != entryId) {
            return waiting to Action.LateOrStale(ERROR_LATE_OR_STALE)
        }
        return InvocationState.Terminal to Action.Publish
    }

    enum class Kind {
        STRUCTURE,
        SCREENSHOT,
    }
}
