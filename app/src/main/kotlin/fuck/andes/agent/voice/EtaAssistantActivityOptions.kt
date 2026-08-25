package fuck.andes.agent.voice

import android.app.ActivityOptions
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi

internal object EtaAssistantActivityOptions {
    fun senderOptions(): Bundle {
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT < 34) {
            @Suppress("DEPRECATION")
            options.isPendingIntentBackgroundActivityLaunchAllowed = true
        } else {
            setModernSenderOptions(options)
        }
        return options.toBundle()
    }

    @RequiresApi(34)
    private fun setModernSenderOptions(options: ActivityOptions) {
        options.pendingIntentBackgroundActivityStartMode = if (Build.VERSION.SDK_INT >= 36) {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
        } else {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }
    }
}
