package fuck.andes.agent.voice

/**
 * 入口小窗在运行终态时的展示策略。
 */
internal enum class EtaAssistantCompletionSurface {
    /** 保持当前窗口状态，不重建。 */
    KEEP_CURRENT,

    /** 恢复入口小窗，允许继续输入。 */
    RESTORE_ENTRY,
}

/**
 * 决定 [EtaAssistantOverlayService] 在运行终态时如何展示入口小窗。
 */
internal object EtaAssistantCompletionSurfacePolicy {
    /**
     * 根据入口小窗是否因为前台工具被隐藏，决定终态展示策略。
     *
     * @param hiddenForForegroundOperation 入口小窗是否因前台工具被临时隐藏
     * @return [RESTORE_ENTRY] 如果曾被隐藏，需要恢复；[KEEP_CURRENT] 保持当前窗口
     */
    fun resolve(hiddenForForegroundOperation: Boolean): EtaAssistantCompletionSurface =
        if (hiddenForForegroundOperation) EtaAssistantCompletionSurface.RESTORE_ENTRY
        else EtaAssistantCompletionSurface.KEEP_CURRENT
}