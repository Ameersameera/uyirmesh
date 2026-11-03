package com.ameer.uyirmesh.sos

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.core.Json
import com.ameer.uyirmesh.core.str
import com.ameer.uyirmesh.util.Perms
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Offline "help help" detector (Vosk speech recognition, Indian English small model, runs on the phone).
 * The recogniser is limited to a tiny grammar so it only has to tell "help" apart from everything else.
 * Two "help"s in one utterance start the SOS countdown.
 */
class VoiceTrigger(private val ctx: Context, private val onHelp: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var speech: SpeechService? = null
    private var paused = false
    private var stopped = true
    private var lastFire = 0L

    fun start() {
        stopped = false
        paused = false
        current = this
        if (!Perms.hasMic(ctx)) {
            _status.value = STATUS_NO_PERMISSION
            return
        }
        val m = model
        if (m != null) {
            listen(m)
            return
        }
        if (loading) return
        val hasModel = try {
            ctx.assets.list("model")?.isNotEmpty() == true
        } catch (e: Exception) {
            false
        }
        if (!hasModel) {
            _status.value = STATUS_NO_MODEL
            return
        }
        loading = true
        _status.value = STATUS_LOADING
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            StorageService.unpack(ctx.applicationContext, "model", "model",
                { loaded ->
                    loading = false
                    model = loaded
                    Graph.log("VOICE", "Speech model ready")
                    if (!stopped && !paused) listen(loaded)
                },
                { e ->
                    loading = false
                    _status.value = STATUS_ERROR
                    Graph.log("VOICE", "Speech model failed: ${e.message}")
                })
        } catch (e: Throwable) {
            loading = false
            _status.value = STATUS_ERROR
            Graph.log("VOICE", "Speech engine failed: ${e.message}")
        }
    }

    private fun listen(m: Model) {
        if (stopped || paused || speech != null) return
        try {
            val rec = Recognizer(m, 16000f, GRAMMAR)
            val s = SpeechService(rec, 16000f)
            s.startListening(listener)
            speech = s
            _status.value = STATUS_LISTENING
        } catch (e: Throwable) {
            speech = null
            _status.value = STATUS_MIC_BUSY
            main.postDelayed({ if (!stopped && !paused) model?.let { listen(it) } }, 20_000)
        }
    }

    private fun release() {
        val s = speech ?: return
        speech = null
        try {
            s.stop()
        } catch (_: Throwable) {
        }
        try {
            s.shutdown()
        } catch (_: Throwable) {
        }
    }

    /** Frees the microphone (for voice notes or calls). */
    fun pause() {
        paused = true
        release()
        if (!stopped) _status.value = STATUS_PAUSED
    }

    fun resume() {
        if (stopped) return
        paused = false
        main.postDelayed({ model?.let { listen(it) } }, 500)
    }

    fun stop() {
        stopped = true
        release()
        if (current === this) current = null
        _status.value = STATUS_OFF
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) = check(hypothesis, "partial")
        override fun onResult(hypothesis: String?) = check(hypothesis, "text")
        override fun onFinalResult(hypothesis: String?) = check(hypothesis, "text")

        override fun onError(exception: Exception?) {
            Graph.log("VOICE", "Recogniser error: ${exception?.message}")
            release()
            if (!stopped && !paused) main.postDelayed({ model?.let { listen(it) } }, 5_000)
        }

        override fun onTimeout() {
            release()
            if (!stopped && !paused) model?.let { listen(it) }
        }
    }

    private fun check(json: String?, field: String) {
        if (json.isNullOrBlank()) return
        val text = try {
            Json.parseObject(json).str(field)
        } catch (e: Exception) {
            return
        }
        if (text.isBlank()) return
        val helps = HELP.findAll(text.lowercase()).count()
        if (field == "text" && helps > 0) _lastHeard.value = text
        if (helps >= 2) {
            val now = System.currentTimeMillis()
            if (now - lastFire < 20_000) return
            lastFire = now
            Graph.log("VOICE", "Heard \"$text\"")
            onHelp()
        }
    }

    companion object {
        private const val GRAMMAR = "[\"help\", \"help help\", \"help me\", \"[unk]\"]"
        private val HELP = Regex("\\bhelp\\b")

        const val STATUS_OFF = "off"
        const val STATUS_LOADING = "loading"
        const val STATUS_LISTENING = "listening"
        const val STATUS_PAUSED = "paused"
        const val STATUS_NO_PERMISSION = "no_permission"
        const val STATUS_NO_MODEL = "no_model"
        const val STATUS_MIC_BUSY = "mic_busy"
        const val STATUS_ERROR = "error"

        @Volatile var current: VoiceTrigger? = null
            private set
        @Volatile private var model: Model? = null
        @Volatile private var loading = false

        private val _status = MutableStateFlow(STATUS_OFF)
        val status: StateFlow<String> = _status
        private val _lastHeard = MutableStateFlow("")
        val lastHeard: StateFlow<String> = _lastHeard

        val modelLoaded: Boolean get() = model != null
    }
}
