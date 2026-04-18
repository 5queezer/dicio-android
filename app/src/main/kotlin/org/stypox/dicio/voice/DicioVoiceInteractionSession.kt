package org.stypox.dicio.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import org.stypox.dicio.MainActivity

// Hands off to MainActivity's existing ACTION_ASSIST handler so we don't rebuild the
// STT / skill-evaluation pipeline inside the session itself.
class DicioVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_ASSIST
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(EXTRA_FROM_VOICE_INTERACTION, true)
        }
        startAssistantActivity(intent)
        hide()
    }

    companion object {
        const val EXTRA_FROM_VOICE_INTERACTION = "org.stypox.dicio.voice.FROM_VOICE_INTERACTION"
    }
}
