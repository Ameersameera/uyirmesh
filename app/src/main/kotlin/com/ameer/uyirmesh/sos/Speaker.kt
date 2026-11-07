package com.ameer.uyirmesh.sos

import android.content.Context
import android.speech.tts.TextToSpeech
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.ui.L
import java.util.Locale

/**
 * Reads SOS updates aloud ("Your SOS reached the rescue base") for people who cannot look at the screen,
 * cannot read well, or are in the dark. Uses the phone's own text-to-speech, which works offline. When the
 * phone has no voice for the app language, the English text is read instead.
 */
class Speaker(private val ctx: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var localVoice = false
    private var voiceLang = ""
    private val pending = ArrayList<Pair<String, String>>()

    /** Speaks [key] (with {placeholders} filled from [args]) unless reading aloud is switched off. */
    @Synchronized
    fun say(key: String, vararg args: Pair<String, String>) {
        if (!Graph.prefs.now.speakUpdates) return
        var local = L.t(key)
        var english = L.get("en", key)
        for ((k, v) in args) {
            local = local.replace("{$k}", v)
            english = english.replace("{$k}", v)
        }
        if (tts == null) {
            try {
                tts = TextToSpeech(ctx.applicationContext, this)
            } catch (e: Exception) {
                Graph.note("tts", e)
                return
            }
        }
        if (!ready) {
            pending.add(local to english)
            return
        }
        speak(local, english)
    }

    @Synchronized
    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Graph.log("VOICE", "Text-to-speech not available on this phone")
            return
        }
        ready = true
        chooseVoice(t)
        for ((local, english) in pending) speak(local, english)
        pending.clear()
    }

    private fun chooseVoice(t: TextToSpeech) {
        val lang = Graph.prefs.now.lang
        if (lang == voiceLang) return
        voiceLang = lang
        localVoice = false
        if (lang != "en") {
            val res = try {
                t.setLanguage(Locale(lang, "IN"))
            } catch (e: Exception) {
                TextToSpeech.LANG_NOT_SUPPORTED
            }
            localVoice = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (!localVoice) {
            try {
                t.setLanguage(Locale("en", "IN"))
            } catch (_: Exception) {
                t.setLanguage(Locale.ENGLISH)
            }
        }
    }

    private fun speak(local: String, english: String) {
        val t = tts ?: return
        try {
            chooseVoice(t)
            val text = if (localVoice || Graph.prefs.now.lang == "en") local else english
            t.speak(text, TextToSpeech.QUEUE_ADD, null, "uyir-${System.nanoTime()}")
        } catch (e: Exception) {
            Graph.note("tts speak", e)
        }
    }

    @Synchronized
    fun shutdown() {
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ready = false
        voiceLang = ""
    }
}
