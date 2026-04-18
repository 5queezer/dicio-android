// Activates only after the user manually selects Dicio in Settings -> Apps -> Default apps -> Digital assistant app.
package org.stypox.dicio.voice

import android.service.voice.VoiceInteractionService

/**
 * Root [VoiceInteractionService] so the OS can bind Dicio as the default digital assistant,
 * enabling hands-free invocation from the lockscreen (power-button long-press / assist gesture).
 *
 * The actual per-invocation work happens in [DicioVoiceInteractionSessionService] /
 * [DicioVoiceInteractionSession]. This class exists so the OS has a service to bind to and
 * so the manifest can point at the voice interaction metadata XML.
 */
class DicioVoiceInteractionService : VoiceInteractionService()
