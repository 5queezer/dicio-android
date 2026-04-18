package org.stypox.dicio.voice

import android.content.Context
import android.content.Intent
import android.service.voice.VoiceInteractionSession
import org.stypox.dicio.MainActivity

/**
 * Session launched by the OS for a single assistant invocation. Rather than rebuilding the
 * STT / skill-evaluation pipeline here, we simply hand off to [MainActivity] with an
 * [Intent.ACTION_ASSIST] intent — the activity's existing handler will pick it up and start
 * listening. The session is closed right after, since all further interaction happens in the
 * activity.
 */
class DicioVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: android.os.Bundle?, showFlags: Int) {
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

        // All further interaction happens in MainActivity; close the session window.
        hide()
    }

    companion object {
        const val EXTRA_FROM_VOICE_INTERACTION = "org.stypox.dicio.voice.FROM_VOICE_INTERACTION"
    }
}
