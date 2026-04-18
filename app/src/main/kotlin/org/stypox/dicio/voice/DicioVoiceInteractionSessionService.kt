package org.stypox.dicio.voice

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Bound by the framework to spawn a new [VoiceInteractionSession] each time the user triggers
 * the assistant gesture / long-press. We just return a [DicioVoiceInteractionSession] which
 * immediately hands off to [org.stypox.dicio.MainActivity].
 */
class DicioVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return DicioVoiceInteractionSession(this)
    }
}
