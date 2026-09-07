package fuck.andes.agent.voice

import android.app.Service
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import fuck.andes.agent.media.AgentImageCodec
import fuck.andes.agent.model.AgentModelClient
import fuck.andes.agent.overlay.AgentOverlayVisibilityPolicy
import fuck.andes.agent.runtime.AgentEvent
import fuck.andes.agent.runtime.AgentExternalArchivePayload
import fuck.andes.agent.runtime.AgentRuntimeClient
import fuck.andes.agent.runtime.AgentRuntimeWire
import fuck.andes.core.AndroidAgentLogger
import fuck.andes.agent.device.RootShellDeviceController
import fuck.andes.agent.device.RootShellDeviceController.ScreenCaptureEncoding
import fuck.andes.ui.MainActivity
import fuck.andes.ui.app.AgentAppTheme
import fuck.andes.data.model.AppearanceSettings
import fuck.andes.data.repository.AppearanceSettingsRepository
import fuck.andes.ui.app.AgentRunMessageProjector
import fuck.andes.ui.model.AgentChatMessageUi
import fuck.andes.ui.model.AgentMessageUi
import fuck.andes.ui.model.ThinkingMessageUi
import fuck.andes.ui.model.SystemNoticeCode
import fuck.andes.ui.model.SystemNoticeMessageUi
import fuck.andes.ui.model.ToolActivityMessageUi
import fuck.andes.ui.model.TokenUsageUi
import fuck.andes.ui.model.UserMessageUi
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

/**
 * Eta 数字助理的用户界面窗口。
 *
 * 系统助理会话只负责承接电源键入口；这里固定使用全屏 TYPE_APPLICATION_OVERLAY，
 * 让输入法、动画和厂商助手式浮窗拥有同一个窗口生命周期。
 */
internal class EtaAssistantOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancellationExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EtaAssistantRuntimeCancel")
    }
    private val runtimeClient = AgentRuntimeClient(this, AndroidAgentLogger)
    private val runMessageProjector = AgentRunMessageProjector()
    private val conversationKey = "eta_assistant_${UUID.randomUUID()}"
    private var conversationHistory = emptyList<AgentModelClient.ConversationMessage>()

    private var windowManager: WindowManager? = null
    private var windowView: ComposeView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var detachingWindowView: View? = null
    private val windowDetachCallbacks = mutableListOf<(Boolean) -> Unit>()
    private var backInvokedDispatcher: OnBackInvokedDispatcher? = null
    private var backInvokedCallback: OnBackInvokedCallback? = null
    private var runJob: Job? = null
    private var assistJob: Job? = null
    private var fallbackJob: Job? = null
    private var activeRunId: String? = null
    private var entryGeneration = 0L
    private var presentedEntryGeneration = -1L
    private var screenContextAttachment: EtaScreenContextAttachment? = null
    private var activeAssistEntryId: String? = null
    private var assistReadySignal: CompletableDeferred<Unit>? = null
    private var entryState: EtaAssistEntryReducer.State =
        EtaAssistEntryReducer.waiting(entryId = "", generation = 0L, deadlineElapsed = 0L)
    private var screenContextText = ""
    private var hiddenForForegroundOperation = false
    private var handoffInProgress = false
    private var handoffExitRequested by mutableStateOf(false)
    private var inputText by mutableStateOf("")
    private var inputFocusRequestKey by mutableIntStateOf(-1)
    private var uiState by mutableStateOf(EtaVoiceUiState())

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        activeService = this
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showEntry(intent.getStringExtra(EXTRA_ENTRY_ID))
            ACTION_ASSIST_CONTEXT_READY -> assistContextReady(intent.getStringExtra(EXTRA_ENTRY_ID).orEmpty())
            ACTION_HANDOFF_READY -> finishHandoff()
            else -> showEntry(null)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        entryGeneration++
        cancelAssistAndFallback()
        dispatchDismiss()
        screenContextAttachment = null
        cancelCurrentRun()
        removeWindow()
        scope.cancel()
        cancellationExecutor.shutdown()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    private fun showEntry(assistEntryId: String? = null) {
        if (!Settings.canDrawOverlays(this)) {
            AndroidAgentLogger.warnThrottled("eta_assistant_overlay_permission_missing") {
                "Eta assistant overlay permission is missing"
            }
            stopSelf()
            return
        }
        cancelCurrentRun()
        cancelAssistAndFallback()
        removeWindow()
        val generation = ++entryGeneration
        activeAssistEntryId = assistEntryId
        entryState = EtaAssistEntryReducer.waiting(
            entryId = assistEntryId.orEmpty(),
            generation = generation,
            deadlineElapsed = SystemClock.elapsedRealtime() + ASSIST_CONTEXT_TIMEOUT_MS,
        )
        presentedEntryGeneration = -1L
        screenContextAttachment = null
        screenContextText = ""
        inputText = ""
        uiState = EtaVoiceUiState(
            screenContext = EtaScreenContextUiState(
                phase = EtaScreenContextPhase.CAPTURING,
            ),
        )
        hiddenForForegroundOperation = false
        handoffInProgress = false
        handoffExitRequested = false
        if (assistEntryId == null) {
            startFallback(generation)
            return
        }
        // Assist-first：立即显示 CAPTURING UI 并尝试 consume；1,000 ms 单调 deadline 内
        // 没有 ready action 就进入统一截图入口的 fallback。
        presentEntry(generation)
        val readySignal = CompletableDeferred<Unit>()
        assistReadySignal = readySignal
        assistJob = scope.launch {
            runAssistFlow(generation, assistEntryId, readySignal)
        }
    }

    private suspend fun runAssistFlow(
        generation: Long,
        assistEntryId: String,
        readySignal: CompletableDeferred<Unit>,
    ) {
        val store = EtaAssistContextStore(this@EtaAssistantOverlayService)
        val first = withContext(Dispatchers.IO) { store.consume(assistEntryId) }
        if (first != null) {
            withContext(Dispatchers.Main.immediate) { onConsumed(generation, assistEntryId, first) }
            return
        }
        val waiting = entryState as? EtaAssistEntryReducer.State.Waiting
        val waitMs = waiting?.deadlineElapsed
            ?.let { deadline -> deadline - SystemClock.elapsedRealtime() }
            ?.coerceAtLeast(0L) ?: 0L
        val firedByReady = try {
            withTimeout(waitMs) { readySignal.await() }
            true
        } catch (_: TimeoutCancellationException) {
            false
        }
        if (firedByReady) {
            val second = withContext(Dispatchers.IO) { store.consume(assistEntryId) }
            if (second != null) {
                withContext(Dispatchers.Main.immediate) { onConsumed(generation, assistEntryId, second) }
                return
            }
            withContext(Dispatchers.Main.immediate) {
                if (
                    dispatchEvent(EtaAssistEntryReducer.Event.ReadyMissingOrInvalid) ==
                    EtaAssistEntryReducer.Action.StartFallback
                ) {
                    startFallback(generation)
                }
            }
        } else {
            withContext(Dispatchers.IO) { store.discard(assistEntryId) }
            withContext(Dispatchers.Main.immediate) {
                if (
                    dispatchEvent(EtaAssistEntryReducer.Event.Deadline) ==
                    EtaAssistEntryReducer.Action.StartFallback
                ) {
                    startFallback(generation)
                }
            }
        }
    }

    private fun onConsumed(
        generation: Long,
        assistEntryId: String,
        consumed: EtaAssistContextStore.Consumed,
    ) {
        if (generation != entryGeneration) return
        val structureText = consumed.screenContextText
        val hasStructure = structureText.isNotBlank()
        val attachment = consumed.imageBytes?.let { bytes ->
            val image = AgentImageCodec.fromEncodedBytes(
                bytes = bytes,
                mimeType = consumed.imageMimeType,
                width = consumed.imageWidth,
                height = consumed.imageHeight,
                source = "system_assist",
            ) ?: return@let null
            val preview = runCatching {
                AgentImageCodec.previewFromReference(this@EtaAssistantOverlayService, image)
            }.getOrNull() ?: return@let null
            EtaScreenContextAttachment(image, preview.reference)
        }
        val hasImage = attachment != null
        if (!hasImage && !hasStructure) {
            // 两种内容都无效：discard 并 fallback，不记录 assist/partial 终态。
            EtaAssistContextStore(this).discard(assistEntryId)
            startFallback(generation)
            return
        }
        if (
            dispatchEvent(EtaAssistEntryReducer.Event.ConsumeSucceeded) !=
            EtaAssistEntryReducer.Action.PresentAssist
        ) {
            return
        }
        assistJob = null
        assistReadySignal = null
        screenContextText = structureText
        screenContextAttachment = attachment
        EtaAssistContextHealthStore.write(
            this,
            if (hasImage && hasStructure) {
                EtaAssistContextHealthStore.OUTCOME_ASSIST_CONSUMED
            } else {
                EtaAssistContextHealthStore.OUTCOME_PARTIAL
            },
            hasImage = hasImage,
            hasStructure = hasStructure,
        )
        uiState = uiState.copy(
            screenContext = EtaScreenContextUiState(
                phase = EtaScreenContextPhase.AVAILABLE,
                previewDataUrl = attachment?.previewDataUrl,
            ),
        )
        presentEntry(generation)
    }

    private fun startFallback(generation: Long) {
        if (generation != entryGeneration) return
        entryState = EtaAssistEntryReducer.State.FallbackStarted
        EtaAssistContextHealthStore.write(
            this,
            EtaAssistContextHealthStore.OUTCOME_FALLBACK_STARTED,
            hasImage = false,
            hasStructure = false,
        )
        val controller = RootShellDeviceController(AndroidAgentLogger)
        fallbackJob = scope.launch {
            val result = controller.captureScreenshot(
                encoding = ScreenCaptureEncoding.SCREEN_CONTEXT,
            )
            val attachment = result.image?.let { image ->
                runCatching {
                    val preview = AgentImageCodec.previewFromReference(
                        this@EtaAssistantOverlayService,
                        image,
                    ) ?: return@runCatching null
                    EtaScreenContextAttachment(image, preview.reference)
                }.getOrNull()
            }
            withContext(Dispatchers.Main.immediate) {
                if (generation != entryGeneration) return@withContext
                fallbackJob = null
                screenContextAttachment = attachment
                uiState = uiState.copy(
                    screenContext = attachment?.let {
                        EtaScreenContextUiState(
                            phase = EtaScreenContextPhase.AVAILABLE,
                            previewDataUrl = it.previewDataUrl,
                        )
                    } ?: EtaScreenContextUiState(phase = EtaScreenContextPhase.UNAVAILABLE),
                )
                presentEntry(generation)
            }
        }
    }

    private fun assistContextReady(assistEntryId: String) {
        if (assistEntryId.isBlank()) return
        val state = entryState
        if (
            state is EtaAssistEntryReducer.State.Waiting &&
            state.entryId == assistEntryId &&
            state.generation == entryGeneration
        ) {
            assistReadySignal?.complete(Unit)
            return
        }
        if (
            dispatchEvent(EtaAssistEntryReducer.Event.LateReady) ==
            EtaAssistEntryReducer.Action.Discard
        ) {
            val store = EtaAssistContextStore(this)
            scope.launch(Dispatchers.IO) { store.discard(assistEntryId) }
        }
    }

    private fun dispatchEvent(event: EtaAssistEntryReducer.Event): EtaAssistEntryReducer.Action {
        val (next, action) = EtaAssistEntryReducer.reduce(entryState, event)
        entryState = next
        return action
    }

    private fun dispatchDismiss() {
        if (
            dispatchEvent(EtaAssistEntryReducer.Event.Dismiss) ==
            EtaAssistEntryReducer.Action.Discard
        ) {
            EtaAssistContextHealthStore.write(
                this,
                EtaAssistContextHealthStore.OUTCOME_CANCELLED,
                hasImage = false,
                hasStructure = false,
            )
            activeAssistEntryId?.let { entryId -> EtaAssistContextStore(this).discard(entryId) }
        }
    }

    private fun cancelAssistAndFallback() {
        assistJob?.cancel()
        assistJob = null
        fallbackJob?.cancel()
        fallbackJob = null
        assistReadySignal = null
    }

    private fun presentEntry(generation: Long) {
        if (generation != entryGeneration || presentedEntryGeneration == generation) return
        presentedEntryGeneration = generation
        showWindow()
        if (windowView == null) {
            stopSelf()
            return
        }
        showKeyboard()
    }

    private fun showWindow() {
        if (windowView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val view = createComposeView {
            val appearance by AppearanceSettingsRepository.settingsFlow()
                .collectAsState(initial = AppearanceSettings())
            AgentAppTheme(
                appearance = appearance,
                applyInterfaceScale = false,
            ) {
                // ColorOS 在 Overlay 窗口切换期间可能短暂使用软件画布；RuntimeShader
                // 无法在该画布绘制，因此浮窗统一使用 Miuix 的圆角回退路径。
                CompositionLocalProvider(LocalSquircleEnabled provides false) {
                    EtaVoicePanel(
                        state = uiState,
                        input = inputText,
                        inputFocusRequestKey = inputFocusRequestKey,
                        onInputChange = { inputText = it },
                        onScreenContextSelect = ::selectScreenContext,
                        onScreenContextRemove = ::removeScreenContext,
                        onSubmit = ::submitInput,
                        onStop = ::stopCurrentRun,
                        onClose = ::dismissAndStop,
                        canOpenConversation = activeRunId == null &&
                            uiState.messages.any { message ->
                                message is AgentMessageUi && message.content.isNotBlank()
                            },
                        exitRequested = handoffExitRequested,
                        onOpenConversation = ::openConversation,
                    )
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            dimAmount = 0f
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            title = "EtaAssistantOverlay"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && wm.isCrossWindowBlurEnabled) {
                flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                blurBehindRadius = 24
            }
        }
        runCatching { wm.addView(view, params) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("eta_assistant_overlay_add_failed") {
                "Eta assistant overlay addView failed: type=${throwable.javaClass.simpleName}"
            }
            return
        }
        windowManager = wm
        windowView = view
        windowParams = params
        registerSystemBackCallback(view)
        view.requestFocus()
    }

    private fun createComposeView(content: @Composable () -> Unit): ComposeView =
        ComposeView(this).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isFocusableInTouchMode = true
            setViewTreeLifecycleOwner(this@EtaAssistantOverlayService)
            setViewTreeSavedStateRegistryOwner(this@EtaAssistantOverlayService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent(content)
        }

    private fun registerSystemBackCallback(view: View) {
        unregisterSystemBackCallback()
        val dispatcher = view.findOnBackInvokedDispatcher()
        if (dispatcher == null) {
            AndroidAgentLogger.warn("Eta assistant overlay back dispatcher unavailable")
            return
        }
        val callback = OnBackInvokedCallback(::dismissAndStop)
        dispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            callback,
        )
        backInvokedDispatcher = dispatcher
        backInvokedCallback = callback
    }

    private fun unregisterSystemBackCallback() {
        val dispatcher = backInvokedDispatcher
        val callback = backInvokedCallback
        backInvokedDispatcher = null
        backInvokedCallback = null
        if (dispatcher != null && callback != null) {
            dispatcher.unregisterOnBackInvokedCallback(callback)
        }
    }

    private fun showKeyboard(status: EtaVoiceStatus = EtaVoiceStatus.InputRequest) {
        uiState = uiState.copy(
            phase = EtaVoicePhase.READY,
            status = status,
        )
        updateSoftInput(visible = true)
        inputFocusRequestKey++
    }

    private fun submitInput() {
        val prompt = inputText.trim()
        if (prompt.isBlank() || activeRunId != null) return
        submitPrompt(prompt)
    }

    private fun submitPrompt(prompt: String) {
        val normalized = prompt.trim()
        if (normalized.isBlank() || activeRunId != null) return
        // 在把 UI 状态改成 CONSUMED 前一次性捕获 selection snapshot；
        // 禁止在 coroutine 中重新读取已经重置的 uiState/screenContext 字段。
        val includeScreenContext = uiState.screenContext.selected
        val selectedAttachment = screenContextAttachment.takeIf { includeScreenContext }
        val runImages = selectedAttachment?.let { listOf(it.image) }.orEmpty()
        val previewImages = selectedAttachment?.let { listOf(it.previewDataUrl) }.orEmpty()
        val snapshotScreenContextText = if (includeScreenContext) screenContextText else ""
        screenContextAttachment = null
        screenContextText = ""
        inputText = ""
        activeRunId = UUID.randomUUID().toString()
        val runId = activeRunId ?: return
        uiState = uiState.copy(
            phase = EtaVoicePhase.PROCESSING,
            status = EtaVoiceStatus.Reasoning,
            screenContext = EtaScreenContextStateReducer.consume(),
            messages = uiState.messages + UserMessageUi(
                id = "user-$runId",
                content = normalized,
                images = previewImages,
            ),
        )
        updateSoftInput(visible = false)
        runJob = scope.launch {
            val config = AgentModelClient.loadConfig()
            val payload = AgentExternalArchivePayload(
                userText = normalized,
                conversationKey = conversationKey,
                title = normalized.take(40),
            )
            val result = runtimeClient.run(
                request = AgentRuntimeWire.RunRequest(
                    runId = runId,
                    prompt = normalized,
                    config = config,
                    images = runImages,
                    history = conversationHistory,
                    screenContextText = snapshotScreenContextText,
                    handoff = AgentRuntimeWire.EntryHandoff(
                        id = "$conversationKey:$runId",
                        source = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
                        payload = payload.toJson(),
                        dismissEntrySurfaceOnForegroundOperation = true,
                    ),
                ),
                onEvent = { event -> handleRuntimeEvent(runId, event) },
            )
            val completionSurface = withContext(Dispatchers.Main.immediate) {
                if (activeRunId != runId) return@withContext EtaAssistantCompletionSurface.KEEP_CURRENT
                activeRunId = null
                runJob = null
                if (result.ok) {
                    conversationHistory = conversationHistory +
                        AgentModelClient.buildUserHistoryMessage(normalized, runImages) +
                        result.transcript
                    uiState = uiState.copy(
                        phase = EtaVoicePhase.READY,
                        status = EtaVoiceStatus.Completed,
                        messages = finishRunMessages(runId, result),
                    )
                } else {
                    uiState = uiState.copy(
                        phase = EtaVoicePhase.ERROR,
                        status = EtaVoiceStatus.Failed(result.error),
                        messages = finishRunMessages(runId, result),
                    )
                }
                if (!hiddenForForegroundOperation) {
                    updateSoftInput(visible = false)
                }
                EtaAssistantCompletionSurfacePolicy.resolve(hiddenForForegroundOperation)
            }
            runtimeClient.ackResult(runId)
            withContext(Dispatchers.Main.immediate) {
                if (activeRunId == null) {
                    when (completionSurface) {
                        EtaAssistantCompletionSurface.RESTORE_ENTRY -> {
                            hiddenForForegroundOperation = false
                            showWindow()
                            showKeyboard()
                            if (windowView == null) stopSelf()
                        }
                        EtaAssistantCompletionSurface.KEEP_CURRENT -> {
                            // 保持现有窗口，不重建、不停止 Service
                        }
                    }
                }
            }
        }
    }

    private fun handleRuntimeEvent(runId: String, event: AgentEvent) {
        scope.launch(Dispatchers.Main.immediate) {
            if (activeRunId != runId) return@launch
            if (AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(event)) {
                hideForForegroundOperation()
            }
            uiState = projectRuntimeEvent(runId, event, uiState)
        }
    }

    private fun projectRuntimeEvent(
        runId: String,
        event: AgentEvent,
        state: EtaVoiceUiState,
    ): EtaVoiceUiState {
        var messages = state.messages
        var status = state.status
        var phase = state.phase
        when (event) {
            is AgentEvent.AssistantBlockStart -> {
                messages = runMessageProjector.startAssistantBlock(runId, event, messages)
            }
            is AgentEvent.ModelRetryScheduled -> {
                messages = runMessageProjector.scheduleModelRetry(runId, event, messages)
                status = EtaVoiceStatus.Reasoning
            }


            is AgentEvent.AssistantBlockDelta -> {
                messages = when (event.kind) {
                    AgentEvent.AssistantBlockKind.TEXT ->
                        runMessageProjector.appendTextDelta(
                            runId,
                            event.round,
                            event.index,
                            event.delta,
                            messages,
                        )

                    AgentEvent.AssistantBlockKind.THINKING ->
                        runMessageProjector.appendReasoningDelta(
                            runId,
                            event.round,
                            event.index,
                            event.delta,
                            messages,
                        )

                    AgentEvent.AssistantBlockKind.TOOL_CALL -> messages
                }
            }

            is AgentEvent.AssistantBlockEnd -> {
                messages = when (event.kind) {
                    AgentEvent.AssistantBlockKind.TEXT ->
                        runMessageProjector.finalizeTextBlock(
                            runId,
                            event.round,
                            event.index,
                            event.replacementContent,
                            messages,
                        )

                    AgentEvent.AssistantBlockKind.THINKING ->
                        runMessageProjector.finalizeThinkingBlock(
                            runId,
                            event.round,
                            event.index,
                            event.replacementContent,
                            messages,
                        )

                    AgentEvent.AssistantBlockKind.TOOL_CALL -> messages
                }
            }

            is AgentEvent.UsageReceived -> {
                val assistantPrefix = "assistant-$runId-${event.round}"
                val usage = TokenUsageUi(
                    contextTokens = event.usage.contextTokens,
                    inputTokens = event.usage.inputTokens,
                    outputTokens = event.usage.outputTokens,
                    reasoningTokens = event.usage.reasoningTokens,
                    cachedTokens = event.usage.cachedTokens,
                )
                val targetIndex = messages.indexOfLast { message ->
                    message is AgentMessageUi &&
                        (message.id == assistantPrefix || message.id.startsWith("$assistantPrefix-"))
                }
                messages = messages.mapIndexed { index, message ->
                    if (index == targetIndex && message is AgentMessageUi) {
                        message.copy(usage = usage)
                    } else {
                        message
                    }
                }
            }

            is AgentEvent.UserSupplementReceived -> {
                val id = "user-$runId-supplement-${event.index}"
                if (messages.none { it.id == id }) {
                    messages = messages + UserMessageUi(id = id, content = event.text)
                }
            }

            is AgentEvent.ToolStarted -> {
                status = EtaVoiceStatus.RunningTool(event.name)
                messages = runMessageProjector.startTool(
                    runId,
                    event,
                    runMessageProjector.finalizeTextRound(
                        runId,
                        event.round,
                        runMessageProjector.finalizeThinkingRound(runId, event.round, messages),
                    ),
                )
            }

            is AgentEvent.ToolFinished -> {
                messages = runMessageProjector.finishTool(runId, event, messages)
            }

            is AgentEvent.HostedToolStarted -> {
                status = EtaVoiceStatus.RunningTool(event.name)
                messages = runMessageProjector.startHostedTool(
                    runId,
                    event,
                    runMessageProjector.finalizeTextRound(
                        runId,
                        event.round,
                        runMessageProjector.finalizeThinkingRound(runId, event.round, messages),
                    ),
                )
            }

            is AgentEvent.HostedToolFinished -> {
                messages = runMessageProjector.finishHostedTool(runId, event, messages)
            }

            is AgentEvent.RunFailed -> {
                phase = EtaVoicePhase.ERROR
                status = EtaVoiceStatus.Failed(event.reason)
                messages = runMessageProjector.failRunningTools(
                    event.reason,
                    runMessageProjector.finalizeText(
                        runId,
                        runMessageProjector.finalizeThinking(runId, messages),
                    ),
                )
            }

            is AgentEvent.AssistantReceived -> {
                if (event.reasoningContent.isNotBlank()) {
                    messages = runMessageProjector.ensureCompletedThinking(
                        runId = runId,
                        round = event.round,
                        content = event.reasoningContent,
                        messages = messages,
                    )
                }
            }

            is AgentEvent.RunFinished -> {
                messages = runMessageProjector.finalizeText(
                    runId,
                    runMessageProjector.finalizeThinking(runId, messages),
                )
            }

            is AgentEvent.ProviderRequestStarted -> status = EtaVoiceStatus.Reasoning
            is AgentEvent.RunStarted,
            is AgentEvent.ProviderResponseStarted,
            is AgentEvent.ToolImagesAttached,
            is AgentEvent.RoundStarted,
            -> Unit
        }
        return state.copy(messages = messages, phase = phase, status = status)
    }

    private fun finishRunMessages(
        runId: String,
        result: AgentRuntimeWire.RunResult,
    ): List<AgentChatMessageUi> {
        var messages = runMessageProjector.finalizeText(
            runId,
            runMessageProjector.finalizeThinking(runId, uiState.messages),
        )
        if (!result.ok) {
            messages = runMessageProjector.failRunningTools(
                result.error ?: SYNTHETIC_RUNTIME_FAILED,
                messages,
            )
        }
        val notice = when {
            result.ok && result.content.isBlank() -> SystemNoticeCode.EmptyResult
            !result.ok && result.error == LEGACY_STOPPED_ERROR -> SystemNoticeCode.Stopped
            !result.ok -> SystemNoticeCode.RuntimeFailed
            else -> null
        }
        val lastAssistantIndex = AgentRunMessageProjector.resultTargetIndex(runId, messages)
        messages = if (lastAssistantIndex >= 0) {
            val targetRound = (messages[lastAssistantIndex] as AgentMessageUi).id
                .assistantRound(runId)
            val sameRoundBlocks = targetRound?.let { round ->
                messages.count { message ->
                    message is AgentMessageUi && message.id.assistantRound(runId) == round
                }
            } ?: 0
            messages.mapIndexed { index, message ->
                if (index == lastAssistantIndex && message is AgentMessageUi) {
                    if (notice == null) {
                        message.copy(
                            content = if (sameRoundBlocks <= 1) {
                                result.content
                            } else {
                                message.content.ifBlank { result.content }
                            },
                            isStreaming = false,
                            renderMarkdown = true,
                        )
                    } else {
                        SystemNoticeMessageUi(
                            id = message.id,
                            code = notice,
                            detail = result.error.takeIf { notice == SystemNoticeCode.RuntimeFailed },
                        )
                    }
                } else {
                    message
                }
            }
        } else {
            if (notice == null) {
                messages + AgentMessageUi(
                    id = AgentRunMessageProjector.resultFallbackId(runId, messages),
                    content = result.content,
                    isStreaming = false,
                    renderMarkdown = true,
                )
            } else {
                messages + SystemNoticeMessageUi(
                    id = AgentRunMessageProjector.resultFallbackId(runId, messages),
                    code = notice,
                    detail = result.error.takeIf { notice == SystemNoticeCode.RuntimeFailed },
                )
            }
        }
        runMessageProjector.clearRun(runId)
        return messages
    }

    private fun String.assistantRound(runId: String): Int? {
        val prefix = "assistant-$runId-"
        return removePrefix(prefix)
            .takeIf { it != this }
            ?.substringBefore('-')
            ?.toIntOrNull()
    }

    private fun stopCurrentRun() {
        val runId = activeRunId
        if (runId != null) {
            activeRunId = null
            requestRuntimeCancellation(runId)
            runJob?.cancel()
            runJob = null
            uiState = uiState.copy(
                phase = EtaVoicePhase.READY,
                status = EtaVoiceStatus.Stopped,
                messages = runMessageProjector.failRunningTools(
                    SYNTHETIC_STOPPED,
                    runMessageProjector.finalizeText(
                        runId,
                        runMessageProjector.finalizeThinking(runId, uiState.messages),
                    ),
                ),
            )
            runMessageProjector.clearRun(runId)
            updateSoftInput(visible = true)
            inputFocusRequestKey++
        } else {
            dismissAndStop()
        }
    }

    private fun cancelCurrentRun() {
        val runId = activeRunId ?: return
        activeRunId = null
        requestRuntimeCancellation(runId)
        runJob?.cancel()
        runJob = null
    }

    private fun requestRuntimeCancellation(runId: String) {
        runCatching {
            cancellationExecutor.execute { runtimeClient.cancelRun(runId) }
        }
    }

    private fun updateSoftInput(visible: Boolean) {
        val wm = windowManager ?: return
        val view = windowView ?: return
        val params = windowParams ?: return
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
            if (visible) {
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            } else {
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            }
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun selectScreenContext() {
        val hasContext = screenContextAttachment != null || screenContextText.isNotBlank()
        uiState = uiState.copy(
            screenContext = EtaScreenContextStateReducer.select(
                state = uiState.screenContext,
                enabled = activeRunId == null,
                hasContext = hasContext,
            ),
        )
    }

    private fun removeScreenContext() {
        uiState = uiState.copy(
            screenContext = EtaScreenContextStateReducer.remove(
                state = uiState.screenContext,
                enabled = activeRunId == null,
            ),
        )
    }

    private fun hideForForegroundOperation(onComplete: ((Boolean) -> Unit)? = null) {
        hiddenForForegroundOperation = true
        EtaVoiceInteractionSession.requestHideForForegroundOperation(this)
        removeWindow(onComplete)
    }

    private fun removeWindow(onComplete: ((Boolean) -> Unit)? = null) {
        unregisterSystemBackCallback()
        detachingWindowView?.let { detachingView ->
            onComplete?.let(windowDetachCallbacks::add)
            if (!detachingView.isAttachedToWindow) {
                finishWindowDetach(success = true)
            }
            return
        }

        val view = windowView
        val wm = windowManager
        if (view == null || wm == null || !view.isAttachedToWindow) {
            windowView = null
            windowParams = null
            windowManager = null
            onComplete?.invoke(true)
            return
        }

        onComplete?.let(windowDetachCallbacks::add)
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                view.removeOnAttachStateChangeListener(this)
                finishWindowDetach(success = true)
            }
        }
        detachingWindowView = view
        view.addOnAttachStateChangeListener(attachListener)

        val removed = runCatching {
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
            wm.removeView(view)
            true
        }.getOrElse { throwable ->
            view.removeOnAttachStateChangeListener(attachListener)
            AndroidAgentLogger.warnThrottled("eta_assistant_overlay_remove_failed") {
                "Eta assistant overlay removeView failed: type=${throwable.javaClass.simpleName}"
            }
            false
        }
        if (!removed) {
            finishWindowDetach(success = false)
            return
        }

        windowView = null
        windowParams = null
        windowManager = null
        if (!view.isAttachedToWindow) {
            view.removeOnAttachStateChangeListener(attachListener)
            finishWindowDetach(success = true)
        }
    }

    private fun finishWindowDetach(success: Boolean) {
        detachingWindowView = null
        val callbacks = windowDetachCallbacks.toList()
        windowDetachCallbacks.clear()
        callbacks.forEach { callback -> callback(success) }
    }

    private fun dismissAndStop() {
        entryGeneration++
        cancelAssistAndFallback()
        dispatchDismiss()
        screenContextAttachment = null
        cancelCurrentRun()
        removeWindow()
        stopSelf()
    }

    private fun openConversation() {
        if (handoffInProgress || activeRunId != null || uiState.messages.isEmpty()) return
        handoffInProgress = true
        AndroidAgentLogger.info("Eta assistant handoff requested")
        updateSoftInput(visible = false)
        val intent = Intent(this, MainActivity::class.java)
            .setAction(ACTION_OPEN_CONVERSATION)
            .putExtra(EXTRA_CONVERSATION_KEY, conversationKey)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
        val creatorOptions = ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                pendingIntentCreatorBackgroundActivityStartMode =
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            HANDOFF_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            creatorOptions.toBundle(),
        )
        @Suppress("NewApi") runCatching { pendingIntent.send(EtaAssistantActivityOptions.senderOptions()) }
            .onFailure {
                handoffInProgress = false
                AndroidAgentLogger.warn("Eta assistant handoff activity launch failed")
                return
            }
        scope.launch(Dispatchers.Main.immediate) {
            delay(HANDOFF_TIMEOUT_MS)
            if (handoffInProgress) {
                AndroidAgentLogger.warn("Eta assistant handoff timed out waiting for chat")
                handoffInProgress = false
            }
        }
    }

    private fun finishHandoff() {
        if (!handoffInProgress) return
        if (handoffExitRequested) return
        AndroidAgentLogger.info("Eta assistant handoff chat ready")
        handoffExitRequested = true
        scope.launch(Dispatchers.Main.immediate) {
            delay(HANDOFF_EXIT_DURATION_MS)
            handoffInProgress = false
            removeWindow()
            stopSelf()
        }
    }

    internal companion object {
        const val ACTION_SHOW = "fuck.andes.agent.voice.SHOW"
        const val ACTION_OPEN_CONVERSATION = "fuck.andes.agent.voice.OPEN_CONVERSATION"
        const val ACTION_ASSIST_CONTEXT_READY = "fuck.andes.agent.voice.ASSIST_CONTEXT_READY"
        const val EXTRA_CONVERSATION_KEY = "fuck.andes.agent.voice.extra.CONVERSATION_KEY"
        const val EXTRA_ENTRY_ID = "fuck.andes.agent.voice.extra.ENTRY_ID"
        private const val ACTION_HANDOFF_READY = "fuck.andes.agent.voice.HANDOFF_READY"
        private const val HANDOFF_TIMEOUT_MS = 5_000L
        private const val HANDOFF_EXIT_DURATION_MS = 220L
        private const val HANDOFF_REQUEST_CODE = 0x455441
        private const val FOREGROUND_DISMISS_TIMEOUT_MS = 2_000L
        private const val LEGACY_STOPPED_ERROR = "已停止"
        private const val SYNTHETIC_STOPPED = "eta_status:stopped"
        private const val SYNTHETIC_RUNTIME_FAILED = "eta_status:runtime_failed"
        private const val ASSIST_CONTEXT_TIMEOUT_MS = 1_000L
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var activeService: EtaAssistantOverlayService? = null

        /**
         * Eta 自己拥有入口浮层，直接关闭并等待具体 View detach；不能按包名猜测，
         * 因为入口、Runtime 与结果浮层都属于同一个包。
         */
        fun dismissForForegroundOperation(context: Context): Boolean {
            val service = activeService
            if (service == null) {
                EtaVoiceInteractionSession.requestHideForForegroundOperation(context)
                return true
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                service.hideForForegroundOperation()
                return service.windowView == null && service.detachingWindowView == null
            }

            val completed = CountDownLatch(1)
            val dismissed = AtomicBoolean(false)
            mainHandler.post {
                val current = activeService
                if (current == null) {
                    EtaVoiceInteractionSession.requestHideForForegroundOperation(context)
                    dismissed.set(true)
                    completed.countDown()
                } else if (current !== service) {
                    completed.countDown()
                } else {
                    current.hideForForegroundOperation { success ->
                        dismissed.set(success)
                        completed.countDown()
                    }
                }
            }
            return try {
                completed.await(FOREGROUND_DISMISS_TIMEOUT_MS, TimeUnit.MILLISECONDS) &&
                    dismissed.get()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }

        fun show(context: Context) {
            context.applicationContext.startService(
                Intent(context.applicationContext, EtaAssistantOverlayService::class.java)
                    .setAction(ACTION_SHOW),
            )
        }

        fun show(context: Context, entryId: String) {
            context.applicationContext.startService(
                Intent(context.applicationContext, EtaAssistantOverlayService::class.java)
                    .setAction(ACTION_SHOW)
                    .putExtra(EXTRA_ENTRY_ID, entryId),
            )
        }

        fun assistContextReady(context: Context, entryId: String) {
            context.applicationContext.startService(
                Intent(context.applicationContext, EtaAssistantOverlayService::class.java)
                    .setAction(ACTION_ASSIST_CONTEXT_READY)
                    .putExtra(EXTRA_ENTRY_ID, entryId),
            )
        }

        fun dismiss(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, EtaAssistantOverlayService::class.java),
            )
        }

        fun notifyHandoffReady(context: Context) {
            context.applicationContext.startService(
                Intent(context.applicationContext, EtaAssistantOverlayService::class.java)
                    .setAction(ACTION_HANDOFF_READY),
            )
        }
    }
}

private data class EtaScreenContextAttachment(
    val image: AgentModelClient.ModelImage,
    val previewDataUrl: String,
)
