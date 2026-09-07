package fuck.andes.agent.voice

import android.graphics.Bitmap

/**
 * 单次 invoke 的所有权协调器：独占 reducer state、structure、bitmap 和 timer handle。
 *
 * 四个入口 [begin]、[onStructure]、[onScreenshot]、[destroy] 只能由同一个 executor
 * 串行调用。publish transition 先关闭 timer 并把 payload 移入不可变
 * [PublishSnapshot] 后清空自身引用，迟到 screenshot 只回收自己的 copy，
 * 迟到 structure 只发固定错误码。
 */
internal class EtaAssistInvocationCoordinator(
    private val schedulePartial: (delayMs: Long, task: () -> Unit) -> AutoCloseable,
    private val publish: (PublishSnapshot) -> Unit,
    private val onDrop: (errorCode: String) -> Unit,
) {
    private var state: EtaAssistInvocationReducer.InvocationState =
        EtaAssistInvocationReducer.InvocationState.Idle
    private var entryId: String? = null
    private var structureText: String = ""
    private var screenshotCopy: Bitmap? = null
    private var timerHandle: AutoCloseable? = null

    fun begin(token: String) {
        destroy()
        entryId = token
        structureText = ""
        screenshotCopy = null
        state = EtaAssistInvocationReducer.begin(token)
    }

    fun onStructure(token: String, text: String, nowElapsed: Long) {
        if (token != entryId) {
            onDrop(EtaAssistInvocationReducer.ERROR_LATE_OR_STALE)
            return
        }
        val (next, action) = EtaAssistInvocationReducer.onCallback(
            state, token, EtaAssistInvocationReducer.Kind.STRUCTURE, nowElapsed,
        )
        structureText = text
        applyAction(next, action, nowElapsed)
    }

    fun onScreenshot(token: String, bitmap: Bitmap, nowElapsed: Long) {
        if (token != entryId) {
            onDrop(EtaAssistInvocationReducer.ERROR_LATE_OR_STALE)
            return
        }
        val (next, action) = EtaAssistInvocationReducer.onCallback(
            state, token, EtaAssistInvocationReducer.Kind.SCREENSHOT, nowElapsed,
        )
        // 回收旧 copy
        if (screenshotCopy !== bitmap) {
            val old = screenshotCopy
            screenshotCopy = null
            if (old != null && !old.isRecycled) old.recycle()
        }
        screenshotCopy = bitmap
        applyAction(next, action, nowElapsed)
    }

    fun destroy() {
        timerHandle?.close()
        timerHandle = null
        val old = screenshotCopy
        screenshotCopy = null
        if (old != null && !old.isRecycled) old.recycle()
        entryId = null
        structureText = ""
        state = EtaAssistInvocationReducer.InvocationState.Idle
    }

    private fun applyAction(
        next: EtaAssistInvocationReducer.InvocationState,
        action: EtaAssistInvocationReducer.Action,
        nowElapsed: Long,
    ) {
        when (action) {
            is EtaAssistInvocationReducer.Action.LateOrStale -> {
                onDrop(action.errorCode)
            }

            is EtaAssistInvocationReducer.Action.SchedulePartial -> {
                state = next
                if (timerHandle == null) {
                    val currentId = entryId ?: return
                    timerHandle = schedulePartial(300L) {
                        onPartialTimeout(currentId, nowElapsed + 300L)
                    }
                }
            }

            is EtaAssistInvocationReducer.Action.Publish -> {
                state = next
                publishNow()
            }

            is EtaAssistInvocationReducer.Action.None -> { /* 不改变状态，通常用于 Idle */ }
        }
    }

    private fun onPartialTimeout(token: String, nowElapsed: Long) {
        timerHandle?.close()
        timerHandle = null
        if (token != entryId) return
        val (next, action) = EtaAssistInvocationReducer.onPartialTimeout(
            state, token, nowElapsed,
        )
        when (action) {
            is EtaAssistInvocationReducer.Action.Publish -> {
                state = next
                publishNow()
            }
            is EtaAssistInvocationReducer.Action.LateOrStale -> {
                onDrop(action.errorCode)
            }
            else -> { /* 不应出现 */ }
        }
    }

    private fun publishNow() {
        timerHandle?.close()
        timerHandle = null
        val id = entryId ?: return
        val text = structureText
        val copy = screenshotCopy
        screenshotCopy = null
        publish(PublishSnapshot(entryId = id, structureText = text, screenshotCopy = copy))
    }

    data class PublishSnapshot(
        val entryId: String,
        val structureText: String,
        val screenshotCopy: Bitmap?,
    )
}