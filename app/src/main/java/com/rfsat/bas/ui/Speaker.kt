package com.rfsat.bas.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Terse spoken corrections/scores so a prone shooter needn't look at the
 *  phone. Off unless RangeSettings.speak() is on; safe to call anytime. */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) runCatching { tts?.language = Locale.getDefault() }
        }
    }

    fun say(context: Context, text: String) {
        if (!RangeSettings.speak()) return
        if (tts == null) { init(context); return }
        if (!ready) return
        runCatching { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bas") }
    }
}
