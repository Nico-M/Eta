package fuck.andes.agent.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession
import android.view.View
import fuck.andes.agent.media.AgentModelImageEncoder
import fuck.andes.core.AndroidAgentLogger
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 系统数字助理的入口桥接。
 *
 * framework 线程只维护当前不可变 [InvocationToken]，并对 framework bitmap 做一次
 * ARGB_8888 software copy；Begin/Structure/Screenshot/Destroy 事件全部投递到专用单线程
 * scheduled executor 后交给 [EtaAssistInvocationCoordinator] 独占 reducer state、
 * structure、bitmap 和 timer handle。
 */
internal class EtaVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private val store = EtaAssistContextStore(context)
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "EtaAssistInvocation").apply { isDaemon = true }
    }
    private val coordinator = EtaAssistInvocationCoordinator(
        schedulePartial = { delayMs, task ->
            val future = scheduler.schedule(task, delayMs, TimeUnit.MILLISECONDS)
            AutoCloseable { future.cancel(false) }
        },
        publish = ::publishSnapshot,
        onDrop = { errorCode ->
            AndroidAgentLogger.warnThrottled(errorCode) {
                "Assist invocation late/stale callback dropped"
            }
        },
    )

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_HIDE_FOR_FOREGROUND_OPERATION) {
                hide()
            }
        }
    }

    // 只允许 framework 回调线程访问。
    private var token: InvocationToken? = null

    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
        context.registerReceiver(
            controlReceiver,
            IntentFilter(ACTION_HIDE_FOR_FOREGROUND_OPERATION),
            Context.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onCreateContentView(): View = View(context)

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        val entryId = args?.getString(EXTRA_ENTRY_ID)?.takeIf { it.isSafeEntryId() }
        // prune 投递到 executor，避免主线程文件 I/O。
        scheduler.execute { runCatching { store.prune() } }
        token = entryId?.let { InvocationToken(entryId = it, generation = SystemClock.elapsedRealtime()) }
        scheduler.execute {
            token?.let { current -> coordinator.begin(current.entryId) }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val current = token
        if (current == null) {
            EtaAssistantOverlayService.show(context)
        } else {
            EtaAssistantOverlayService.show(context, current.entryId)
        }
    }

    override fun onHandleAssist(state: VoiceInteractionSession.AssistState) {
        val current = token ?: run {
            logUnkeyed()
            return
        }
        // 结构只接受 focused state，或 OEM 只返回一个 state 时接受 count == 1；
        // 不合并其它 activity。
        if (!state.isFocused && state.count > 1) {
            AndroidAgentLogger.warnThrottled("assist_context_secondary_state") {
                "Assist secondary state skipped"
            }
            return
        }
        val text = EtaAssistContextProjector.project(state.assistStructure)
        val entryId = current.entryId
        scheduler.execute { coordinator.onStructure(entryId, text, SystemClock.elapsedRealtime()) }
    }

    override fun onHandleScreenshot(bitmap: Bitmap?) {
        val current = token ?: run {
            logUnkeyed()
            return
        }
        if (bitmap == null) return
        // framework 传入对象绝不回收；只做软件 copy，异步线程只回收 copy。
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return
        val entryId = current.entryId
        scheduler.execute { coordinator.onScreenshot(entryId, copy, SystemClock.elapsedRealtime()) }
    }

    /** 在 executor 上编码 snapshot、写 store，并无论 publish 成功或失败都发送 ready。 */
    private fun publishSnapshot(snapshot: EtaAssistInvocationCoordinator.PublishSnapshot) {
        try {
            val encoded = snapshot.screenshotCopy?.let { candidate ->
                runCatching {
                    AgentModelImageEncoder.screenContextEncoded(candidate, source = "system_assist")
                }.getOrNull()
            }
            store.publish(snapshot.entryId, snapshot.structureText, encoded)
        } finally {
            val copy = snapshot.screenshotCopy
            if (copy != null && !copy.isRecycled) copy.recycle()
            EtaAssistantOverlayService.assistContextReady(context, snapshot.entryId)
        }
    }

    override fun onBackPressed() {
        EtaAssistantOverlayService.dismiss(context)
        hide()
    }

    override fun onCloseSystemDialogs() {
        EtaAssistantOverlayService.dismiss(context)
        hide()
    }

    override fun onDestroy() {
        token = null
        // Destroy 投递取消 timer 并回收未提交 bitmap；已排队 publish 在 Destroy 之前完成。
        scheduler.execute { coordinator.destroy() }
        scheduler.shutdown()
        context.unregisterReceiver(controlReceiver)
        super.onDestroy()
    }

    private fun logUnkeyed() {
        AndroidAgentLogger.warnThrottled("assist_context_unkeyed") {
            "Assist callback missing entry id"
        }
    }

    private data class InvocationToken(
        val entryId: String,
        val generation: Long,
    )

    internal companion object {
        private const val ACTION_HIDE_FOR_FOREGROUND_OPERATION =
            "fuck.andes.agent.voice.HIDE_FOR_FOREGROUND_OPERATION"
        const val EXTRA_ENTRY_ID = "fuck.andes.agent.voice.extra.ENTRY_ID"

        fun requestHideForForegroundOperation(context: Context) {
            context.sendBroadcast(
                Intent(ACTION_HIDE_FOR_FOREGROUND_OPERATION)
                    .setPackage(context.packageName),
            )
        }
    }
}

private fun String.isSafeEntryId(): Boolean =
    length in 1..80 && matches(Regex("[A-Za-z0-9-]+"))
