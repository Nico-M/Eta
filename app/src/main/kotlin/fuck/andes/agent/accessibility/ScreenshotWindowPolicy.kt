package fuck.andes.agent.accessibility

/** 截图窗口筛选必须保持"模型看到的"和实际接收触摸的窗口一致。 */
internal object ScreenshotWindowPolicy {
    enum class Decision {
        CAPTURE,
        EXCLUDE,
        BLOCK_UNKNOWN,
    }

    /** API 33 在无排除且无 Eta 无障碍浮层时允许 Root 整屏截图。 */
    enum class CaptureMode {
        ROOT_DISPLAY,
        WINDOWS,
    }

    fun captureMode(
        sdkInt: Int,
        excludedPackagesPresent: Boolean,
        unsafeAccessibilityOverlayPresent: Boolean,
    ): CaptureMode? = when {
        sdkInt >= 34 -> CaptureMode.WINDOWS
        !excludedPackagesPresent && !unsafeAccessibilityOverlayPresent -> CaptureMode.ROOT_DISPLAY
        else -> null
    }

    fun decide(
        isAccessibilityOverlay: Boolean,
        isApplicationWindow: Boolean,
        active: Boolean,
        focused: Boolean,
        resolvedPackage: String?,
        ownPackage: String,
        excludedPackages: Set<String>,
    ): Decision {
        if (isAccessibilityOverlay && resolvedPackage == ownPackage) {
            return Decision.EXCLUDE
        }
        if (resolvedPackage in excludedPackages) return Decision.EXCLUDE
        if (
            excludedPackages.isNotEmpty() &&
            resolvedPackage.isNullOrBlank() &&
            (isApplicationWindow || active || focused)
        ) {
            return Decision.BLOCK_UNKNOWN
        }
        return Decision.CAPTURE
    }
}