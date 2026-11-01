package org.stypox.dicio.eval

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import org.stypox.dicio.io.input.InputEvent
import javax.inject.Inject

/**
 * Debug builds only: feeds text to the skill evaluator as if typed in the app bar, so that an
 * automated benchmark can send any Unicode input without the keyboard:
 * `adb shell am broadcast -a org.stypox.dicio.EVAL_INPUT -p <package> --es text "..."`
 */
@AndroidEntryPoint
class EvalInputReceiver : BroadcastReceiver() {
    @Inject lateinit var skillEvaluator: SkillEvaluator

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        Log.d(TAG, "Eval input: $text")
        skillEvaluator.processInputEvent(InputEvent.Final(listOf(Pair(text, 1.0f))))
    }

    companion object {
        private const val TAG = "EvalInputReceiver"
    }
}
