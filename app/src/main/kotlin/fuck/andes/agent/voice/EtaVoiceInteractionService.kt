package fuck.andes.agent.voice

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import java.util.UUID

class EtaVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        activeService = this
        if (Build.VERSION.SDK_INT >= 37) {
            setInvocationEffectEnabled(true)
        }
    }

    override fun onShutdown() {
        if (activeService === this) activeService = null
        super.onShutdown()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        startActivity(
            Intent(this, EtaVoiceAssistActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun showEtaSession() {
        val entryId = UUID.randomUUID().toString()
        val flags = VoiceInteractionSession.SHOW_WITH_ASSIST or
            VoiceInteractionSession.SHOW_WITH_SCREENSHOT or
            if (Build.VERSION.SDK_INT >= 37) {
                @Suppress("NewApi")
                VoiceInteractionSession.SHOW_WITH_ASSIST_STRUCTURE_SCREEN_CONTENT
            } else {
                0
            }
        showSession(
            Bundle().apply {
                putString(EtaVoiceInteractionSession.EXTRA_ENTRY_ID, entryId)
            },
            flags,
        )
    }

    companion object {
        @Volatile
        private var activeService: EtaVoiceInteractionService? = null

        internal fun requestSession(): Boolean {
            val service = activeService ?: return false
            service.showEtaSession()
            return true
        }
    }
}

class EtaVoiceAssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EtaVoiceInteractionService.requestSession()
        finish()
    }
}
