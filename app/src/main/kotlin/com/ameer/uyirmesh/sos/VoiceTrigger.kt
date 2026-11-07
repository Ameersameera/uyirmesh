package com.ameer.uyirmesh.sos

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import org.vosk.android.StorageService

/**
 * Offline "help help" detector (Vosk speech recognition, Indian English small model, runs on the phone).
 * The recogniser is limited to a tiny grammar so it only has to tell "help" apart from everything else.
 * Two "help"s in one utterance start the SOS countdown.
 *
 * The microphone loop is our own (not Vosk's SpeechService) so that a busy microphone, a phone call or an
 * audio error never crashes the app: the loop stops and tries again later.
 */
class VoiceTrigger(private val ctx: Context, private val onHelp: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var loop: ListenLoop? = null
    @Volatile private var paused = false
    @Volatile private var stopped = true
    private var lastFire = 0L
    private val retry = Runnable { if (!stopped && !paused) model?.let { listen(it) } }

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
        val app = ctx.applicationContext
        Thread({
            try {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val path = StorageService.sync(app, "model", "model")
                val loaded = Model(path)
                main.post {
                    loading = false
                    model = loaded
                    Graph.log("VOICE", "Speech model ready")
                    if (!stopped && !paused) listen(loaded)
                }
            } catch (t: Throwable) {
                main.post {
                    loading = false
                    _status.value = STATUS_ERROR
                    Graph.log("VOICE", "Speech model failed: ${t.javaClass.simpleName} ${t.message ?: ""}")
                }
            }
        }, "voice-model").start()
    }

    private fun listen(m: Model) {
        if (stopped || paused || loop != null) return
        if (!Perms.hasMic(ctx)) {
            _status.value = STATUS_NO_PERMISSION
            return
        }
        val l = ListenLoop(m)
        loop = l
        l.start()
    }

    private fun stopLoop() {
        val l = loop ?: return
        loop = null
        l.running = false
        l.interrupt()
        try {
            l.join(1_500)
        } catch (_: InterruptedException) {
        }
    }

    /** Frees the microphone (for voice notes). */
    fun pause() {
        paused = true
        main.removeCallbacks(retry)
        stopLoop()
        if (!stopped) _status.value = STATUS_PAUSED
    }

    fun resume() {
        if (stopped) return
        paused = false
        main.removeCallbacks(retry)
        main.postDelayed(retry, 600)
    }

    fun stop() {
        stopped = true
        main.removeCallbacks(retry)
        stopLoop()
        if (current === this) current = null
        _status.value = STATUS_OFF
    }

    private fun onLoopEnded(l: ListenLoop, failed: Boolean) {
        main.post {
            if (loop === l) loop = null
            if (failed && !stopped && !paused) {
                _status.value = STATUS_MIC_BUSY
                main.removeCallbacks(retry)
                main.postDelayed(retry, 20_000)
            }
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

    /** Reads 16 kHz audio and feeds it to the recogniser. Any failure ends the loop quietly. */
    private inner class ListenLoop(private val m: Model) : Thread("voice-sos") {
        @Volatile var running = true

        @SuppressLint("MissingPermission")
        override fun run() {
            var record: AudioRecord? = null
            var rec: Recognizer? = null
            var failed = false
            try {
                rec = Recognizer(m, RATE.toFloat(), GRAMMAR)
                val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val chunk = RATE / 5 // 0.2 s of samples
                val bufBytes = maxOf(minBuf, chunk * 2) * 2
                record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes)
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    failed = true
                    return
                }
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    failed = true
                    return
                }
                main.post { if (loop === this) _status.value = STATUS_LISTENING }
                val buf = ShortArray(chunk)
                while (running && !isInterrupted) {
                    val n = record.read(buf, 0, buf.size)
                    if (n < 0) {
                        failed = true
                        break
                    }
                    if (n == 0) continue
                    if (rec.acceptWaveForm(buf, n)) {
                        val r = rec.result
                        main.post { check(r, "text") }
                    } else {
                        val p = rec.partialResult
                        main.post { check(p, "partial") }
                    }
                }
            } catch (t: Throwable) {
                failed = true
            } finally {
                try {
                    record?.stop()
                } catch (_: Throwable) {
                }
                try {
                    record?.release()
                } catch (_: Throwable) {
                }
                try {
                    rec?.close()
                } catch (_: Throwable) {
                }
                onLoopEnded(this, failed && running)
            }
        }
    }

    companion object {
        private const val RATE = 16_000
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

        /**
         * Loads the speech model once (shared with the "help help" trigger) and gives it to [onReady] on the main
         * thread, or null when the model is missing or cannot load. Used by speak-to-fill on the SOS form.
         */
        fun withModel(ctx: Context, onReady: (Model?) -> Unit) {
            val main = Handler(Looper.getMainLooper())
            model?.let { m ->
                main.post { onReady(m) }
                return
            }
            val hasModel = try {
                ctx.assets.list("model")?.isNotEmpty() == true
            } catch (e: Exception) {
                false
            }
            if (!hasModel) {
                main.post { onReady(null) }
                return
            }
            val app = ctx.applicationContext
            Thread({
                var waited = 0
                while (loading && model == null && waited < 60_000) {
                    try {
                        Thread.sleep(250)
                    } catch (_: InterruptedException) {
                        break
                    }
                    waited += 250
                }
                var m = model
                if (m == null) {
                    try {
                        loading = true
                        LibVosk.setLogLevel(LogLevel.WARNINGS)
                        val path = StorageService.sync(app, "model", "model")
                        m = Model(path)
                        model = m
                    } catch (t: Throwable) {
                        m = null
                    } finally {
                        loading = false
                    }
                }
                main.post { onReady(m) }
            }, "voice-model-form").start()
        }
    }
}
